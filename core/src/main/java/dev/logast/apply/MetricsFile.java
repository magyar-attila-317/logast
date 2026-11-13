package dev.logast.apply;

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
 * {@code .logast/metrics.json}: one decision per outcome the logs report and no metric counts
 * (METRIC_OUTCOME_UNMEASURED). {@code "count": "counter" | "tag" | "skip"}; the next scan turns decided entries into
 * AUTO orders, {@code logast apply . --auto} writes them. Kept up to date like fills.json: what you wrote stays,
 * new outcomes are added, finished ones removed, nothing written when nothing changed.
 */
public final class MetricsFile {
    public static final String PATH = ".logast/metrics.json";
    public static final String RULE = "METRIC_OUTCOME_UNMEASURED";
    private static final String README = "One entry per outcome the logs report and no metric counts. Set \"count\" to "
            + "\"counter\" (Outcomes.count(name, outcome): a counter name{outcome=...}), \"tag\" (Outcomes.tag(outcome) on the "
            + "timer already running; only where _tag says available) or \"skip\" (a // logast:ignore comment). Edit \"name\" "
            + "and \"outcome\" freely (lower case, dots). Then: logast apply . --auto. `logast metrics . --format json` has the context.";

    /** A decision read from the file. */
    public record Decision(String count, String name, String outcome) {
    }

    private MetricsFile() {
    }

    public static Map<String, Decision> read(Path root) {
        Map<String, Decision> out = new LinkedHashMap<>();
        raw(root.resolve(PATH)).forEach((id, n) -> {
            if (!id.startsWith("_") && n.isObject() && !n.path("count").asText("").isBlank()) {
                out.put(id, new Decision(n.path("count").asText().strip().toLowerCase(Locale.ROOT),
                        n.path("name").asText("").strip(), n.path("outcome").asText("").strip()));
            }
        });
        return out;
    }

    /** Created when missing, entries kept as written, new ones appended, finished ones removed. Null when nothing to do. */
    public static FillsFile.Update update(Path root, List<WorkOrder> orders) {
        Path file = root.resolve(PATH);
        Map<String, JsonNode> previous = raw(file);
        Map<String, WorkOrder> current = new LinkedHashMap<>();
        orders.stream().filter(o -> o.rule().equals(RULE)).forEach(o -> current.put(o.id(), o));
        if (current.isEmpty() && !Files.exists(file)) {
            return new FillsFile.Update(0, 0, 0, 0, false);
        }
        ObjectNode out = OrderJson.MAPPER.createObjectNode();
        out.put("_readme", README);
        int decided = 0, added = 0, removed = 0;
        for (var e : previous.entrySet()) {
            if (e.getKey().startsWith("_")) {
                continue;
            }
            if (!current.containsKey(e.getKey())) {
                removed++;
                continue;
            }
            out.set(e.getKey(), e.getValue());
            decided += e.getValue().path("count").asText("").isBlank() ? 0 : 1;
        }
        for (var e : current.entrySet()) {
            if (previous.containsKey(e.getKey())) {
                continue;
            }
            Map<String, Object> d = e.getValue().data();
            ObjectNode n = out.putObject(e.getKey());
            n.put("count", "");
            n.put("name", String.valueOf(d.getOrDefault("name", "")));
            n.put("outcome", String.valueOf(d.getOrDefault("outcome", "")));
            n.put("_message", String.valueOf(d.getOrDefault("message", "")));
            n.put("_where", e.getValue().file() + ":" + e.getValue().line() + (e.getValue().method() == null ? "" : " " + e.getValue().method()));
            n.put("_frequency", String.valueOf(d.getOrDefault("frequency", "")));
            n.set("_paths", OrderJson.MAPPER.valueToTree(d.getOrDefault("paths", List.of())));
            n.put("_tag", Boolean.TRUE.equals(d.get("tagAvailable")) ? "available" : "not available here (no observation known to run)");
            added++;
        }
        boolean changed = !Files.exists(file) || added > 0 || removed > 0 || !previous.containsKey("_readme");
        if (changed) {
            try {
                Files.createDirectories(file.getParent());
                OrderJson.MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), out);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        return new FillsFile.Update(current.size(), decided, added, removed, changed);
    }

    private static Map<String, JsonNode> raw(Path file) {
        if (!Files.exists(file)) {
            return Map.of();
        }
        try {
            Map<String, JsonNode> out = new LinkedHashMap<>();
            JsonNode n = OrderJson.MAPPER.readTree(file.toFile());
            if (n instanceof ObjectNode o) {
                o.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue()));
            }
            return out;
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot read " + file + ": " + ex.getMessage(), ex);
        }
    }
}
