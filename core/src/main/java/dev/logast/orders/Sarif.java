package dev.logast.orders;

import dev.logast.rules.Rules;

import java.util.*;

/** SARIF 2.1.0: GitHub / GitLab code scanning turn this into PR annotations without any custom bot. */
public final class Sarif {
    private Sarif() {
    }

    public static Map<String, Object> of(List<WorkOrder> orders) {
        return of(orders, List.of());
    }

    /**
     * @param gaps why the analysis is incomplete ({@code Analysis.Stats.gaps()}): the run is then marked not successful,
     *             each gap a tool notification, so a consumer can tell "no findings" from "not analysed" (#55)
     */
    public static Map<String, Object> of(List<WorkOrder> orders, List<String> gaps) {
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
                    + " [" + o.id() + (o.reportOnly() ? ", report-only" : o.mechanical() ? ", fix: logast apply" : ", fix: needs text") + "]";
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
            // optional property bag (SARIF §3.8): only what is set, so earlier consumers see the same result (#57)
            Map<String, Object> properties = new LinkedHashMap<>();
            if (o.basis() != null) {
                properties.put("basis", o.basis());
            }
            if (o.doneWhen() != null) {
                properties.put("doneWhen", o.doneWhen());
            }
            if (!o.related().isEmpty()) {
                properties.put("related", o.related());
            }
            if (!properties.isEmpty()) {
                result.put("properties", properties);
            }
            results.add(result);
        }
        return Map.of(
                "$schema", "https://json.schemastore.org/sarif-2.1.0.json",
                "version", "2.1.0",
                "runs", List.of(Map.of(
                        "tool", Map.of("driver", Map.of("name", "logast", "informationUri", "https://example.invalid/logast",
                                "rules", rules)),
                        "originalUriBaseIds", Map.of("SRCROOT", Map.of("uri", "file:///")),
                        "invocations", List.of(Map.of(
                                "executionSuccessful", gaps.isEmpty(),
                                "toolExecutionNotifications", gaps.stream().map(g -> Map.of(
                                        "level", "warning", "message", Map.of("text", "analysis incomplete: " + g))).toList())),
                        "results", results)));
    }

    private static Map<String, Object> location(String file, int line) {
        return Map.of("physicalLocation", Map.of(
                "artifactLocation", Map.of("uri", file, "uriBaseId", "SRCROOT"),
                "region", Map.of("startLine", Math.max(line, 1))));
    }
}
