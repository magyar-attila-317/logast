package dev.logast.orders;

import dev.logast.rules.Rules;

import java.util.*;

/** SARIF 2.1.0: GitHub / GitLab code scanning turn this into PR annotations without any custom bot. */
public final class Sarif {
    private Sarif() {
    }

    public static Map<String, Object> of(List<WorkOrder> orders) {
        Set<String> ruleIds = new TreeSet<>();
        orders.forEach(o -> ruleIds.addAll(o.fixes()));
        List<Map<String, Object>> rules = ruleIds.stream().map(r -> Map.<String, Object>of(
                "id", r,
                "shortDescription", Map.of("text", r.replace('_', ' ').toLowerCase(Locale.ROOT)),
                "properties", Map.of("tags", List.copyOf(new TreeSet<>(Rules.packsOf(r)))))).toList();
        List<Map<String, Object>> results = new ArrayList<>();
        for (WorkOrder o : orders) {
            String text = (o.title() == null ? o.rule() : o.title())
                    + (o.suggestion() == null ? "" : " — " + o.suggestion())
                    + " [" + o.id() + (o.reportOnly() ? ", report-only" : o.slots().isEmpty() ? ", fix: logast apply --auto" : ", fix: needs text") + "]";
            List<Map<String, Object>> locations = new ArrayList<>();
            locations.add(location(o.file(), o.line()));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ruleId", o.rule());
            result.put("level", switch (o.severity()) {
                case "high" -> "error";
                case "medium" -> "warning";
                default -> "note";
            });
            result.put("message", Map.of("text", text));
            result.put("locations", locations);
            if (!o.locations().isEmpty()) {
                result.put("relatedLocations", o.locations().stream().limit(50).map(l -> location(l.file(), l.line())).toList());
            }
            result.put("partialFingerprints", Map.of("logastOrderId", o.id()));
            results.add(result);
        }
        return Map.of(
                "$schema", "https://json.schemastore.org/sarif-2.1.0.json",
                "version", "2.1.0",
                "runs", List.of(Map.of(
                        "tool", Map.of("driver", Map.of("name", "logast", "informationUri", "https://example.invalid/logast",
                                "rules", rules)),
                        "originalUriBaseIds", Map.of("SRCROOT", Map.of("uri", "file:///")),
                        "results", results)));
    }

    private static Map<String, Object> location(String file, int line) {
        return Map.of("physicalLocation", Map.of(
                "artifactLocation", Map.of("uri", file, "uriBaseId", "SRCROOT"),
                "region", Map.of("startLine", Math.max(line, 1))));
    }
}
