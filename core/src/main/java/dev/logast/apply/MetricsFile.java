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
            + "and \"outcome\" freely (lower case, dots). Where the line logs a number (_amounts), \"amount\" names one to count too: "
            + "\"as\": \"total\" (a counter name.<amount> grows by it) or \"per-run\" (each value one sample of a distribution "
            + "summary name.<amount>); empty \"as\" takes metrics.amounts from logast.yaml. Leave \"amount\" empty to count "
            + "how often only. Then: logast apply . --auto. `logast metrics . --format json` has the context.";

    /**
     * A decision read from the file, with where its log line was (file, method, event) for when the line's id changed.
     */
    public record Decision(String count, String name, String outcome, String file, String method, String event,
                           String amount, String as) {
        public Decision(String count, String name, String outcome, String file, String method, String event) {
            this(count, name, outcome, file, method, event, "", "");
        }
    }

    /** Where an outcome is logged: what a decision is matched by when its id is gone. */
    public record Spot(String id, String file, String method, String event) {
    }

    /**
     * Decisions for the outcomes found now: by id, else (an entry written under an older id, e.g. the line was
     * reworded since or the id scheme changed) the one decided entry no current outcome claims whose file, method and
     * event match exactly one current outcome. A decision is never dropped for a cosmetic change of its line.
     */
    public static Map<String, Decision> match(Map<String, Decision> decided, List<Spot> current) {
        Map<String, Decision> out = new LinkedHashMap<>();
        Set<String> ids = new HashSet<>();
        current.forEach(s -> ids.add(s.id()));
        for (Spot s : current) {
            if (decided.containsKey(s.id())) {
                out.put(s.id(), decided.get(s.id()));
            }
        }
        for (var e : decided.entrySet()) {
            Decision d = e.getValue();
            if (ids.contains(e.getKey()) || d.file().isEmpty()) {
                continue;
            }
            List<Spot> candidates = current.stream().filter(s -> !out.containsKey(s.id()) && !decided.containsKey(s.id())
                    && s.file().equals(d.file()) && s.method().equals(d.method()) && s.event().equals(d.event())).toList();
            long rivals = decided.entrySet().stream().filter(o -> !ids.contains(o.getKey()) && o.getValue().file().equals(d.file())
                    && o.getValue().method().equals(d.method()) && o.getValue().event().equals(d.event())).count();
            if (candidates.size() == 1 && rivals == 1) {
                out.put(candidates.getFirst().id(), d);
            }
        }
        return out;
    }

    static Spot spot(WorkOrder o) {
        return new Spot(o.id(), o.file(), o.method() == null ? "" : o.method(), String.valueOf(o.data().getOrDefault("event", "")));
    }

    private MetricsFile() {
    }

    public static Map<String, Decision> read(Path root) {
        Map<String, Decision> out = new LinkedHashMap<>();
        raw(root.resolve(PATH)).forEach((id, n) -> {
            if (!id.startsWith("_") && n.isObject() && !n.path("count").asText("").isBlank()) {
                out.put(id, decision(n));
            }
        });
        return out;
    }

    private static Decision decision(JsonNode n) {
        // "_where": "src/…/Job.java:62 Job.doExecute"; "_event" since the ids stopped depending on the message text,
        // before that the suggested outcome was the event
        String where = n.path("_where").asText("");
        int sp = where.indexOf(' ');
        String loc = sp < 0 ? where : where.substring(0, sp);
        int colon = loc.lastIndexOf(':');
        String file = colon < 0 ? loc : loc.substring(0, colon);
        String method = sp < 0 ? "" : where.substring(sp + 1).strip();
        String event = n.path("_event").asText(n.path("outcome").asText("")).strip();
        return new Decision(n.path("count").asText().strip().toLowerCase(Locale.ROOT), n.path("name").asText("").strip(),
                n.path("outcome").asText("").strip(), file, method, event, n.path("amount").asText("").strip(),
                n.path("as").asText("").strip().toLowerCase(Locale.ROOT));
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
        // decisions written under an id that changed move to the outcome's current id instead of being dropped
        Map<String, Decision> decidedBefore = new LinkedHashMap<>();
        previous.forEach((id, n) -> {
            if (!id.startsWith("_") && n.isObject() && !n.path("count").asText("").isBlank()) {
                decidedBefore.put(id, decision(n));
            }
        });
        Map<String, Decision> carried = match(decidedBefore, current.values().stream().map(MetricsFile::spot).toList());
        Set<String> moved = new HashSet<>();
        carried.forEach((id, d) -> decidedBefore.forEach((oldId, od) -> {
            if (od == d && !oldId.equals(id)) {
                moved.add(oldId);
            }
        }));
        int decided = 0, added = 0, removed = 0, problems = 0;
        boolean problemsChanged = false;
        for (var e : previous.entrySet()) {
            if (e.getKey().startsWith("_")) {
                continue;
            }
            if (!current.containsKey(e.getKey())) {
                removed += moved.contains(e.getKey()) ? 0 : 1;
                continue;
            }
            // why a decision cannot be written, next to it (and gone once it can)
            ObjectNode entry = e.getValue().deepCopy();
            Object problem = current.get(e.getKey()).data().get("problem");
            if (problem != null) {
                entry.put("_problem", String.valueOf(problem));
                problems++;
            } else {
                entry.remove("_problem");
            }
            problemsChanged |= !entry.equals(e.getValue());
            out.set(e.getKey(), entry);
            decided += e.getValue().path("count").asText("").isBlank() ? 0 : 1;
        }
        for (var e : current.entrySet()) {
            if (previous.containsKey(e.getKey())) {
                continue;
            }
            Map<String, Object> d = e.getValue().data();
            ObjectNode n = out.putObject(e.getKey());
            Decision kept = carried.get(e.getKey());
            n.put("count", kept == null ? "" : kept.count());
            n.put("name", kept != null && !kept.name().isBlank() ? kept.name() : String.valueOf(d.getOrDefault("name", "")));
            n.put("outcome", kept != null && !kept.outcome().isBlank() ? kept.outcome() : String.valueOf(d.getOrDefault("outcome", "")));
            if (d.get("amounts") instanceof List<?> amounts && !amounts.isEmpty()) {
                n.put("amount", kept == null ? "" : kept.amount());
                n.put("as", kept == null ? "" : kept.as());
                n.set("_amounts", OrderJson.MAPPER.valueToTree(amounts));
            }
            n.put("_event", String.valueOf(d.getOrDefault("event", "")));
            n.put("_message", String.valueOf(d.getOrDefault("message", "")));
            n.put("_where", e.getValue().file() + ":" + e.getValue().line() + (e.getValue().method() == null ? "" : " " + e.getValue().method()));
            n.put("_frequency", String.valueOf(d.getOrDefault("frequency", "")));
            n.set("_paths", OrderJson.MAPPER.valueToTree(d.getOrDefault("paths", List.of())));
            n.put("_tag", Boolean.TRUE.equals(d.get("tagAvailable")) ? "available" : "not available here (no observation known to run)");
            if (kept == null) {
                added++;
            } else {
                decided++;
            }
        }
        boolean changed = !Files.exists(file) || added > 0 || removed > 0 || problemsChanged || !previous.containsKey("_readme")
                || !README.equals(previous.get("_readme").asText());
        if (changed) {
            try {
                Files.createDirectories(file.getParent());
                OrderJson.MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), out);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        return new FillsFile.Update(current.size(), decided, added, removed, changed, problems);
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
