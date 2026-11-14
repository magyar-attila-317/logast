package dev.logast.apply;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.logast.orders.OrderJson;
import dev.logast.orders.WorkOrder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * {@code .logast/fills.json}: the message texts for NEEDS TEXT orders. {@code logast scan} keeps it up to date (one
 * entry per order, with hints; texts already written are kept), {@code logast apply} uses every entry that has text.
 */
public final class FillsFile {
    public static final String PATH = ".logast/fills.json";
    private static final String README = "Write the message text for each order (\"MSG\"), then run: logast apply . --auto  "
            + "Plain text, no {} and no [k=v]: logast adds the values. Leave an entry empty to skip it. "
            + "Keys starting with _ are hints. `logast scan` keeps this file up to date and keeps what you wrote.";

    private FillsFile() {
    }

    /** One entry per order with slots: {"MSG": "", "_MSG": hint, "_where": file:line}. */
    public static Map<String, Object> template(List<WorkOrder> orders) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (WorkOrder o : orders) {
            if (o.slots().isEmpty()) {
                continue;
            }
            Map<String, Object> fill = new LinkedHashMap<>();
            o.slots().forEach((name, slot) -> fill.put(name, ""));
            o.slots().forEach((name, slot) -> fill.put("_" + name, slot.describe()
                    + (slot.original() == null ? "" : " | was: " + slot.original())
                    + (slot.pattern() == null ? "" : " | must read as: " + slot.pattern())
                    + (slot.keys().isEmpty() ? "" : " | suffix keys added by the tool: " + slot.keys())));
            fill.put("_where", o.file() + ":" + o.line() + (o.method() == null ? "" : " " + o.method()));
            out.put(o.id(), fill);
        }
        return out;
    }

    /** What {@link #update} changed: entries now, entries with text, added, removed, whether the file was written. */
    /** @param problems filled entries that cannot be written (each says why in the file) */
    public record Update(int entries, int filled, int added, int removed, boolean written, int problems) {
        public Update(int entries, int filled, int added, int removed, boolean written) {
            this(entries, filled, added, removed, written, 0);
        }
    }

    /**
     * Brings the file up to date without recreating it: existing entries stay exactly as they are and where they are
     * (texts, args, hints), entries for new orders are appended, entries whose order is gone (applied, or no longer
     * found) are removed. Nothing is written when nothing changed; the file is created when it does not exist.
     */
    public static Update update(Path root, List<WorkOrder> orders) {
        Path file = root.resolve(PATH);
        Map<String, JsonNode> previous = readRaw(file);
        Map<String, Object> template = template(orders);
        ObjectNode out = OrderJson.MAPPER.createObjectNode();
        out.set("_readme", previous.getOrDefault("_readme", OrderJson.MAPPER.getNodeFactory().textNode(README)));
        // always says where things stand, so an empty file reads as "nothing to write", not as "something went wrong"
        out.put("_status", template.isEmpty()
                ? "Nothing needs a message text right now: every finding is fixed automatically or only reported."
                : template.size() + " finding(s) below need a message text.");
        int filled = 0, removed = 0, added = 0;
        for (var e : previous.entrySet()) {
            if (e.getKey().startsWith("_")) {
                continue;
            }
            if (!template.containsKey(e.getKey())) {
                removed++;
                continue;
            }
            ObjectNode node = e.getValue() instanceof ObjectNode o ? o.deepCopy() : OrderJson.MAPPER.createObjectNode();
            @SuppressWarnings("unchecked")
            Map<String, Object> fresh = (Map<String, Object>) template.get(e.getKey());
            fresh.forEach((k, v) -> {
                if (!node.has(k)) {
                    node.set(k, OrderJson.MAPPER.valueToTree(v));   // a slot or hint the order gained
                }
            });
            boolean any = false;
            for (var it = node.fields(); it.hasNext(); ) {
                var f = it.next();
                if (!f.getKey().startsWith("_") && !f.getKey().equals("args") && f.getValue().isTextual() && !f.getValue().asText().isBlank()) {
                    any = true;
                }
            }
            filled += any ? 1 : 0;
            out.set(e.getKey(), node);
        }
        for (var e : template.entrySet()) {
            if (!previous.containsKey(e.getKey())) {
                out.set(e.getKey(), OrderJson.MAPPER.valueToTree(e.getValue()));
                added++;
            }
        }
        boolean exists = Files.exists(file);
        boolean changed = !exists || added > 0 || removed > 0 || !out.equals(OrderJson.MAPPER.valueToTree(previous));
        if (changed) {
            try {
                Files.createDirectories(file.getParent());
                OrderJson.MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), out);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        return new Update(template.size(), filled, added, removed, changed);
    }

    /** Entries with text in every slot (empty ones are skipped, hints ignored). */
    public static Map<String, Fill> read(Path file) {
        Map<String, Fill> out = new LinkedHashMap<>();
        readRaw(file).forEach((id, node) -> {
            if (id.startsWith("_") || !node.isObject()) {
                return;
            }
            Fill f = OrderJson.MAPPER.convertValue(node, Fill.class);
            if (!f.slots().isEmpty() && f.slots().values().stream().noneMatch(String::isBlank)) {
                out.put(id, f);
            }
        });
        return out;
    }

    private static Map<String, JsonNode> readRaw(Path file) {
        if (!Files.exists(file)) {
            return Map.of();
        }
        try {
            JsonNode root = OrderJson.MAPPER.readTree(file.toFile());
            Map<String, JsonNode> out = new LinkedHashMap<>();
            if (root instanceof ObjectNode o) {
                o.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue()));
            }
            return out;
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot read " + file + ": " + ex.getMessage(), ex);
        }
    }

    /** Stores texts written elsewhere (the review page) into the file, so scan and apply see them too. */
    public static void save(Path root, Map<String, Fill> fills) {
        Path file = root.resolve(PATH);
        Map<String, JsonNode> raw = new LinkedHashMap<>(readRaw(file));
        fills.forEach((id, f) -> {
            ObjectNode node = raw.get(id) instanceof ObjectNode o ? o : OrderJson.MAPPER.createObjectNode();
            f.slots().forEach(node::put);
            if (!f.args().isEmpty()) {
                node.set("args", OrderJson.MAPPER.valueToTree(f.args()));
            }
            raw.put(id, node);
        });
        try {
            Files.createDirectories(file.getParent());
            OrderJson.MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), raw);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Explicit file (--fills), else the default one when it exists. */
    public static Map<String, Fill> forApply(Path root, Path explicit) {
        if (explicit != null) {
            try {
                Map<String, Fill> all = OrderJson.MAPPER.readValue(explicit.toFile(), new TypeReference<LinkedHashMap<String, Fill>>() {
                });
                all.keySet().removeIf(k -> k.startsWith("_"));
                return all;
            } catch (IOException ex) {
                throw new UncheckedIOException("cannot read " + explicit + ": " + ex.getMessage(), ex);
            }
        }
        return read(root.resolve(PATH));
    }
}
