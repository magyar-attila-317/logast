package dev.logast.apply;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.logast.orders.OrderJson;
import dev.logast.orders.Slot;
import dev.logast.orders.WorkOrder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * {@code .logast/decisions/messages.yaml} (was {@code .logast/fills.json}, #108): the message texts for NEEDS TEXT
 * orders. {@code logast scan} keeps it up to date (one entry per order, with hints; texts already written are kept),
 * {@code logast apply} uses every entry that has text.
 */
public final class FillsFile {
    public static final String PATH = dev.logast.LogastDir.MESSAGES;
    private static final String README = "Optional. Write a message text for an order (MSG), then run: logast apply .\n"
            + "Left blank: nothing changes, the current or generated text stays.\n"
            + "Plain text, no {} and no [k=v]: logast adds the values.\n"
            + "Keys starting with _ are hints (_where and \"was:\" find the line again when its id changes: keep them). "
            + "`logast scan` keeps this file up to date and keeps what you wrote.";

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
        return update(root, orders, rule -> true, true);
    }

    /**
     * @param ran   whether a rule ran in this run (null for an order id logast cannot map to a rule): an entry whose rule
     *              did not run (an apply limited by --pack) is kept as it is, not removed (#275)
     * @param write false for a dry run: work out the update, write nothing
     */
    public static Update update(Path root, List<WorkOrder> orders, java.util.function.Predicate<String> ran, boolean write) {
        Path file = root.resolve(PATH);
        Map<String, JsonNode> previous = DecisionYaml.entries(file);
        Map<String, Object> template = template(orders);
        ObjectNode out = DecisionYaml.MAPPER.createObjectNode();
        // written texts follow their line when its id shifted (a log line added above it in the same method)
        Map<String, Fill> written = new LinkedHashMap<>();
        previous.forEach((id, node) -> {
            if (!id.startsWith("_") && node.isObject()) {
                Fill f = OrderJson.MAPPER.convertValue(node, Fill.class);
                if (!f.slots().isEmpty() && f.slots().values().stream().noneMatch(String::isBlank)) {
                    written.put(id, f);
                }
            }
        });
        Map<String, Fill> placed = match(written, orders);
        Map<String, String> movedTo = new HashMap<>();   // old id -> new id
        placed.forEach((newId, f) -> written.forEach((oldId, g) -> {
            if (g == f && !oldId.equals(newId)) {
                movedTo.put(oldId, newId);
            }
        }));
        int filled = 0, removed = 0, added = 0;
        for (var e : previous.entrySet()) {
            if (e.getKey().startsWith("_")) {
                continue;
            }
            String id = movedTo.getOrDefault(e.getKey(), e.getKey());
            if (!template.containsKey(id) || out.has(id)) {
                if (!out.has(id) && !movedTo.containsKey(e.getKey()) && !ran.test(ruleOf(e.getKey()))) {
                    out.set(e.getKey(), e.getValue().deepCopy());   // another pack's entry: this run cannot judge it
                    continue;
                }
                removed++;
                continue;
            }
            ObjectNode node = e.getValue() instanceof ObjectNode o ? o.deepCopy() : OrderJson.MAPPER.createObjectNode();
            @SuppressWarnings("unchecked")
            Map<String, Object> fresh = (Map<String, Object>) template.get(id);
            if (!id.equals(e.getKey())) {
                fresh.forEach((k, v) -> {
                    if (k.startsWith("_")) {
                        node.set(k, OrderJson.MAPPER.valueToTree(v));   // where the line is now
                    }
                });
            }
            if (written.containsKey(e.getKey()) && !placed.containsKey(id)) {
                node.put("_problem", "this line now reads differently than when the text was written: the text is not used");
            } else {
                node.remove("_problem");
            }
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
            out.set(id, node);
        }
        for (var e : template.entrySet()) {
            if (!out.has(e.getKey())) {
                out.set(e.getKey(), OrderJson.MAPPER.valueToTree(e.getValue()));
                added++;
            }
        }
        // always says where things stand, so an empty file reads as "nothing to write", not as "something went wrong"
        String header = DecisionYaml.comment(README, out.isEmpty()
                ? "Nothing needs a message text right now: every finding is fixed automatically or only reported."
                : out.size() + " finding(s) below need a message text.");
        boolean exists = Files.exists(file);
        ObjectNode before = DecisionYaml.withoutNotes(OrderJson.MAPPER.valueToTree(previous));
        boolean changed = !exists || added > 0 || removed > 0 || !out.equals(before) || !DecisionYaml.header(file).equals(header);
        changed = changed && write && DecisionYaml.write(file, header, out);
        return new Update(template.size(), filled, added, removed, changed);
    }

    /** The rule an order id stands for, from its prefix ({@code FMT-1a2b3c} → LOG_FORMAT), or null. */
    public static String ruleOf(String id) {
        int dash = id.indexOf('-');
        return dash < 0 ? null : dev.logast.rules.OrderBuilder.ruleOfPrefix(id.substring(0, dash));
    }

    /**
     * The fills for the orders found now. An entry is keyed by its order's id, which counts the method's log calls; a
     * log line added above it shifts that count, so the id may now name another line. An entry stays with its id only
     * if the line still reads as it did ("was: …" in its hint); otherwise it moves to the one order in the same method
     * whose line reads that way, or is left out. A text never lands on a line it was not written for.
     */
    public static Map<String, Fill> match(Map<String, Fill> fills, List<WorkOrder> orders) {
        Map<String, WorkOrder> byId = new LinkedHashMap<>();
        orders.forEach(o -> byId.put(o.id(), o));
        Map<String, Fill> out = new LinkedHashMap<>();
        List<Fill> orphans = new ArrayList<>();
        fills.forEach((id, f) -> {
            WorkOrder o = byId.get(id);
            if (o != null && fits(f, o)) {
                out.put(id, f);
            } else {
                orphans.add(f);
            }
        });
        for (Fill f : orphans) {
            if (f.file() == null || f.slots().keySet().stream().allMatch(s -> f.original(s) == null)) {
                continue;   // nothing to recognise its line by
            }
            List<WorkOrder> candidates = orders.stream().filter(o -> !out.containsKey(o.id()) && !fills.containsKey(o.id())
                    && f.file().equals(o.file()) && (f.method() == null || f.method().equals(o.method())) && fits(f, o)).toList();
            if (candidates.size() == 1) {
                out.put(candidates.getFirst().id(), f);
            }
        }
        return out;
    }

    /** The order has the entry's slots, and where both say what the line read before, they agree. */
    static boolean fits(Fill f, WorkOrder o) {
        for (String slot : f.slots().keySet()) {
            Slot s = o.slots().get(slot);
            if (s == null) {
                return false;
            }
            String was = f.original(slot);
            if (was != null && s.original() != null && !was.equals(s.original())) {
                return false;
            }
        }
        return !f.slots().isEmpty();
    }

    /** Entries with text in every slot (empty ones are skipped, hints ignored). */
    public static Map<String, Fill> read(Path file) {
        Map<String, Fill> out = new LinkedHashMap<>();
        DecisionYaml.entries(file).forEach((id, node) -> {
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

    /** Stores texts written elsewhere (the review page) into the file, so scan and apply see them too. */
    public static void save(Path root, Map<String, Fill> fills) {
        Path file = root.resolve(PATH);
        ObjectNode raw = DecisionYaml.withoutNotes(OrderJson.MAPPER.valueToTree(DecisionYaml.entries(file)));
        fills.forEach((id, f) -> {
            ObjectNode node = raw.get(id) instanceof ObjectNode o ? o : raw.putObject(id);
            f.slots().forEach(node::put);
            if (!f.args().isEmpty()) {
                node.set("args", OrderJson.MAPPER.valueToTree(f.args()));
            }
        });
        String header = DecisionYaml.header(file);
        DecisionYaml.write(file, header.isEmpty() ? DecisionYaml.comment(README) : header, raw);
    }

    /** Explicit file (--fills: YAML, or JSON when it ends in .json), else the default one when it exists. */
    public static Map<String, Fill> forApply(Path root, Path explicit) {
        if (explicit != null) {
            try {
                Map<String, Fill> all = new LinkedHashMap<>();
                for (var it = DecisionYaml.read(explicit).fields(); it.hasNext(); ) {
                    var e = it.next();
                    if (!e.getKey().startsWith("_") && e.getValue().isObject()) {
                        all.put(e.getKey(), OrderJson.MAPPER.convertValue(e.getValue(), Fill.class));
                    }
                }
                return all;
            } catch (IOException ex) {
                throw new UncheckedIOException("cannot read " + explicit + ": " + ex.getMessage(), ex);
            }
        }
        return read(root.resolve(PATH));
    }
}
