package dev.logast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Real entrypoint rates (calls per day), provided by the team; logast never guesses them. Two formats:
 * <pre>
 * # .logast/decisions/traffic.yaml
 * entrypoints:
 *   "POST /orders": 120000
 *   "@Scheduled ReconciliationJob.reconcile": 1440
 * patterns:                      # #279: for entrypoints without a value of their own; the most specific match wins
 *   "* /api/admin/**": 10
 *   "GET /api/events/**": 400
 * </pre>
 * or a Prometheus query result saved as JSON, e.g. from
 * {@code sum by (method, uri) (increase(http_server_requests_seconds_count[1d]))}
 * (labels method+uri become "METHOD uri"; a {@code entrypoint} label is used as is).
 */
public final class Traffic {
    private final Map<String, Double> perDay;
    private final String source;
    /** #235: rates computed from schedules in the code, for labels the file has no value for. */
    private final Map<String, ScheduleRate.Rate> schedules;
    /** #279: label glob → calls per day ({@code *}: anything but '/', {@code **}: anything), in file order. */
    private final Map<String, Double> patterns;

    private Traffic(Map<String, Double> perDay, String source) {
        this(perDay, source, Map.of(), Map.of());
    }

    private Traffic(Map<String, Double> perDay, String source, Map<String, ScheduleRate.Rate> schedules, Map<String, Double> patterns) {
        this.perDay = perDay;
        this.source = source;
        this.schedules = schedules;
        this.patterns = patterns;
    }

    /** #235: the same rates, with the schedules' rates for entrypoints the file has none for. */
    public Traffic withSchedules(Map<String, ScheduleRate.Rate> rates) {
        return new Traffic(perDay, source, Map.copyOf(rates), patterns);
    }

    /** #279: the same rates with pattern defaults (label glob → calls per day). */
    public static Traffic of(Map<String, Double> perDay, Map<String, Double> patterns, String source) {
        return new Traffic(new TreeMap<>(perDay), source, Map.of(), new LinkedHashMap<>(patterns));
    }

    public Map<String, Double> patterns() {
        return patterns;
    }

    /** #235: label → the rate computed from its schedule. */
    public Map<String, ScheduleRate.Rate> schedules() {
        return schedules;
    }

    /** #235: the rate computed from an entrypoint's schedule, or null. */
    public ScheduleRate.Rate schedule(String label) {
        return schedules.get(label);
    }

    public static Traffic none() {
        return new Traffic(Map.of(), null);
    }

    public static Traffic of(Map<String, Double> perDay, String source) {
        return new Traffic(new TreeMap<>(perDay), source);
    }

    /** Explicit file, else {@code <root>/.logast/decisions/traffic.yaml|yml|json}, else none. */
    public static Traffic load(Path root, Path explicit) {
        List<Path> candidates = explicit != null ? List.of(explicit)
                : List.of(root.resolve(LogastDir.TRAFFIC), root.resolve(LogastDir.DECISIONS + "/traffic.yml"), root.resolve(LogastDir.DECISIONS + "/traffic.json"));
        for (Path p : candidates) {
            if (Files.exists(p)) {
                return read(p);
            }
        }
        if (explicit != null) {
            throw new IllegalArgumentException("traffic file not found: " + explicit);
        }
        return none();
    }

    static Traffic read(Path p) {
        try {
            JsonNode n = new ObjectMapper(new YAMLFactory()).readTree(Files.readString(p));
            Map<String, Double> out = new TreeMap<>();
            Map<String, Double> patterns = new LinkedHashMap<>();
            n.path("patterns").fields().forEachRemaining(e -> {
                if (e.getValue() != null && !e.getValue().isNull() && !e.getValue().asText().isBlank()) {
                    patterns.put(e.getKey().trim(), e.getValue().asDouble());
                }
            });
            if (n.has("entrypoints") || !patterns.isEmpty() && !n.has("data")) {
                // empty values (from `logast traffic`) mean "no data yet", never zero
                n.path("entrypoints").fields().forEachRemaining(e -> {
                    if (e.getValue() != null && !e.getValue().isNull() && !e.getValue().asText().isBlank()) {
                        out.put(e.getKey(), e.getValue().asDouble());
                    }
                });
            } else if (n.path("data").path("result").isArray()) {
                for (JsonNode r : n.path("data").path("result")) {
                    JsonNode m = r.path("metric");
                    String label = m.has("entrypoint") ? m.get("entrypoint").asText()
                            : m.path("method").asText("ANY") + " " + m.path("uri").asText("");
                    double v = r.path("value").path(1).asDouble(0);
                    out.merge(label.trim(), v, Double::sum);
                }
            } else {
                throw new IllegalArgumentException("unknown traffic format in " + p + " (expected 'entrypoints:' or a Prometheus query result)");
            }
            return new Traffic(out, p.getFileName().toString(), Map.of(), patterns);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Template with every entrypoint (rates empty), keeping rates already filled in {@code existing}; entries for
     * entrypoints that no longer exist are kept as comments so nothing typed in is lost.
     */
    public static String template(List<dev.logast.model.Entrypoint> entrypoints, Traffic existing) {
        StringBuilder sb = new StringBuilder()
                .append("# Calls per day for each entrypoint, from production (e.g. Prometheus:\n")
                .append("#   sum by (method, uri) (increase(http_server_requests_seconds_count[1d])) ).\n")
                .append("# Optional. Left blank: no volume estimate (logast never estimates traffic); log levels and metrics stay cautious.\n")
                .append("# Regenerate with `logast traffic <root>`: filled-in values are kept, new entrypoints are added.\n")
                .append("# patterns: a rate for every entrypoint whose label matches and has no value below (* within a path segment,\n")
                .append("#   ** across segments; the most specific match wins), e.g. \"* /api/admin/**\": 10\n")
                .append("patterns:");
        if (existing.patterns.isEmpty()) {
            sb.append(" {}\n");
        } else {
            sb.append('\n');
            existing.patterns.forEach((k, v) -> sb.append("  \"").append(k.replace("\"", "\\\"")).append("\": ").append(number(v)).append('\n'));
        }
        sb.append("entrypoints:\n");
        Set<String> labels = new LinkedHashSet<>();
        List<dev.logast.model.Entrypoint> sorted = new ArrayList<>(entrypoints);
        sorted.sort(Comparator.comparingInt((dev.logast.model.Entrypoint ep) -> ep.kind() == dev.logast.model.Entrypoint.Kind.EVENT ? 1 : 0)
                .thenComparing(ep -> ep.kind().ordinal()).thenComparing(dev.logast.model.Entrypoint::label));
        // one key per label: endpoints that differ only in produces/consumes/params share one http.server.requests series
        Map<String, List<String>> sameLabel = new LinkedHashMap<>();
        sorted.forEach(ep -> ep.labels().forEach(l -> sameLabel.computeIfAbsent(l, k -> new ArrayList<>()).add(ep.method().label())));
        dev.logast.model.Entrypoint.Kind current = null;
        // a handler mapped to several paths gets a line per path (#51): its rate is their sum
        for (dev.logast.model.Entrypoint ep : sorted) for (String label : ep.labels()) {
            if (labels.contains(label)) {
                continue;
            }
            if (ep.kind() != current) {
                current = ep.kind();
                sb.append(current == dev.logast.model.Entrypoint.Kind.EVENT
                        ? "  # in-process event listeners: their rate follows whoever publishes the event (fill only if you know it)\n"
                        : "  # " + current.name().toLowerCase(Locale.ROOT) + "\n");
            }
            labels.add(label);
            Double v = existing.perDay.get(label);
            ScheduleRate.Rate computed = existing.schedules.get(label);
            sb.append("  \"").append(label.replace("\"", "\\\"")).append("\": ")
                    .append(v == null ? "" : (v == Math.rint(v) ? String.valueOf(v.longValue()) : String.valueOf(v)))
                    .append("    # ").append(ep.kind().name().toLowerCase(Locale.ROOT)).append(", ")
                    .append(String.join(" + ", sameLabel.get(label)))
                    // #235: blank still means the schedule's rate is used; a value written here wins
                    .append(computed == null ? "" : "; from " + computed.from() + ": " + number(computed.perDay()) + "/day")
                    .append('\n');
        }
        List<String> gone = existing.labels().stream().filter(l -> !labels.contains(l)).toList();
        if (!gone.isEmpty()) {
            sb.append("# no longer an entrypoint (kept for reference):\n");
            gone.forEach(l -> sb.append("#  \"").append(l).append("\": ").append(existing.perDay(l)).append('\n'));
        }
        return sb.toString();
    }

    private static String number(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v);
    }

    public boolean known() {
        return !perDay.isEmpty() || !schedules.isEmpty() || !patterns.isEmpty();
    }

    /**
     * Calls per day for an entrypoint label: provided, else computed from its schedule (#235), else the most specific
     * matching pattern (#279); null when none.
     */
    public Double perDay(String entrypointLabel) {
        Double v = perDay.get(entrypointLabel);
        if (v != null) {
            return v;
        }
        if (schedules.containsKey(entrypointLabel)) {
            return schedules.get(entrypointLabel).perDay();
        }
        String best = null;
        for (String p : patterns.keySet()) {
            if (glob(p).matcher(entrypointLabel).matches() && (best == null || specificity(p) > specificity(best))) {
                best = p;
            }
        }
        return best == null ? null : patterns.get(best);
    }

    /** {@code *} matches within a path segment, {@code **} across segments; everything else literally. */
    static java.util.regex.Pattern glob(String pattern) {
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                re.append(".*");
                i++;
            } else if (c == '*') {
                re.append("[^/]*");
            } else {
                re.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
        }
        return java.util.regex.Pattern.compile(re.toString());
    }

    /** The literal characters of a pattern: more of them is a more specific match. */
    private static int specificity(String pattern) {
        return pattern.replace("*", "").length();
    }

    /** Calls per day for an entrypoint: the sum over its labels (a handler mapped to several paths), or null when none is provided. */
    public Double perDay(dev.logast.model.Entrypoint ep) {
        Double sum = null;
        for (String l : ep.labels()) {
            Double v = perDay(l);
            if (v != null) {
                sum = sum == null ? v : sum + v;
            }
        }
        return sum;
    }

    public String source() {
        return schedules.isEmpty() ? source : source == null ? "schedules in the code" : source + " and schedules in the code";
    }

    public Set<String> labels() {
        return perDay.keySet();
    }
}
