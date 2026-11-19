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
 * </pre>
 * or a Prometheus query result saved as JSON, e.g. from
 * {@code sum by (method, uri) (increase(http_server_requests_seconds_count[1d]))}
 * (labels method+uri become "METHOD uri"; a {@code entrypoint} label is used as is).
 */
public final class Traffic {
    private final Map<String, Double> perDay;
    private final String source;

    private Traffic(Map<String, Double> perDay, String source) {
        this.perDay = perDay;
        this.source = source;
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
            if (n.has("entrypoints")) {
                // empty values (from `logast traffic`) mean "no data yet", never zero
                n.get("entrypoints").fields().forEachRemaining(e -> {
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
            return new Traffic(out, p.getFileName().toString());
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
                .append("entrypoints:\n");
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
            Double v = existing.perDay(label);
            sb.append("  \"").append(label.replace("\"", "\\\"")).append("\": ")
                    .append(v == null ? "" : (v == Math.rint(v) ? String.valueOf(v.longValue()) : String.valueOf(v)))
                    .append("    # ").append(ep.kind().name().toLowerCase(Locale.ROOT)).append(", ")
                    .append(String.join(" + ", sameLabel.get(label)))
                    .append('\n');
        }
        List<String> gone = existing.labels().stream().filter(l -> !labels.contains(l)).toList();
        if (!gone.isEmpty()) {
            sb.append("# no longer an entrypoint (kept for reference):\n");
            gone.forEach(l -> sb.append("#  \"").append(l).append("\": ").append(existing.perDay(l)).append('\n'));
        }
        return sb.toString();
    }

    public boolean known() {
        return !perDay.isEmpty();
    }

    /** Calls per day for an entrypoint label, or null when not provided. */
    public Double perDay(String entrypointLabel) {
        return perDay.get(entrypointLabel);
    }

    /** Calls per day for an entrypoint: the sum over its labels (a handler mapped to several paths), or null when none is provided. */
    public Double perDay(dev.logast.model.Entrypoint ep) {
        Double sum = null;
        for (String l : ep.labels()) {
            Double v = perDay.get(l);
            if (v != null) {
                sum = sum == null ? v : sum + v;
            }
        }
        return sum;
    }

    public String source() {
        return source;
    }

    public Set<String> labels() {
        return perDay.keySet();
    }
}
