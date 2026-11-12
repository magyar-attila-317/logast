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

    /**
     * Rewrites the file for the current orders: texts and args already written for an order that still exists are
     * kept; entries of orders that are gone are dropped. Returns {total entries, entries with text}.
     */
    public static int[] update(Path root, List<WorkOrder> orders) {
        Path file = root.resolve(PATH);
        Map<String, JsonNode> previous = readRaw(file);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("_readme", README);
        int filled = 0;
        Map<String, Object> template = template(orders);
        for (var e : template.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fill = (Map<String, Object>) e.getValue();
            JsonNode old = previous.get(e.getKey());
            if (old != null && old.isObject()) {
                boolean any = false;
                for (String slot : List.copyOf(fill.keySet())) {
                    if (!slot.startsWith("_") && old.path(slot).isTextual() && !old.path(slot).asText().isBlank()) {
                        fill.put(slot, old.path(slot).asText());
                        any = true;
                    }
                }
                if (old.has("args")) {
                    fill.put("args", OrderJson.MAPPER.convertValue(old.get("args"), List.class));
                }
                filled += any ? 1 : 0;
            }
            out.put(e.getKey(), fill);
        }
        if (template.isEmpty() && !Files.exists(file)) {
            return new int[]{0, 0};
        }
        try {
            Files.createDirectories(file.getParent());
            OrderJson.MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), out);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return new int[]{template.size(), filled};
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
