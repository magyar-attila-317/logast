package dev.logast.fleet;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.logast.orders.OrderJson;
import dev.logast.orders.ScanResult;
import dev.logast.orders.WorkOrder;
import dev.logast.rules.Rules;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Compliance across services. Score per service and pack: {@code 100 × files / (files + 3·high + 1·medium + 0.25·low)}
 * — 100 only without findings, size-normalised, simple enough to explain. Snapshots are kept so the next report shows
 * the trend.
 */
public final class FleetReport {
    public record Service(String name, String root, int files, int entrypoints, Map<String, Integer> severity,
                          Map<String, Integer> rules, Map<String, Double> packScore, double score,
                          List<String> overrides) {
    }

    public record Snapshot(String created, List<Service> services) {
    }

    private FleetReport() {
    }

    /** @param packs selected packs; unselected ones get no score (shown as "–") */
    public static Service service(String name, ScanResult r, List<String> overrides, List<String> packs) {
        Map<String, Integer> sev = new TreeMap<>();
        Map<String, Integer> rules = new TreeMap<>();
        Map<String, double[]> perPack = new TreeMap<>();
        for (WorkOrder o : r.orders()) {
            sev.merge(o.severity(), 1, Integer::sum);
            o.fixes().forEach(f -> rules.merge(f, 1, Integer::sum));
            for (String pack : Rules.packsOf(o.rule())) {
                perPack.computeIfAbsent(pack, k -> new double[1])[0] += weight(o.severity());
            }
        }
        int files = Math.max(1, r.stats().files());
        Map<String, Double> packScore = new TreeMap<>();
        for (String pack : packs) {
            double w = perPack.containsKey(pack) ? perPack.get(pack)[0] : 0;
            packScore.put(pack, round(100.0 * files / (files + w)));
        }
        double total = r.orders().stream().mapToDouble(o -> weight(o.severity())).sum();
        return new Service(name, r.root(), r.stats().files(), r.stats().entrypoints(), sev, rules, packScore,
                round(100.0 * files / (files + total)), overrides);
    }

    private static double weight(String severity) {
        return switch (severity) {
            case "high" -> 3;
            case "medium" -> 1;
            default -> 0.25;
        };
    }

    private static double round(double d) {
        return Math.round(d * 10) / 10.0;
    }

    /** Writes fleet-&lt;timestamp&gt;.json and fleet.html into {@code out}; returns the HTML path. */
    public static Path write(Path out, List<Service> services) {
        try {
            Files.createDirectories(out);
            Snapshot previous = latest(out);
            Snapshot now = new Snapshot(Instant.now().toString(), services);
            OrderJson.MAPPER.writeValue(out.resolve("fleet-" + now.created().replace(':', '-') + ".json").toFile(), now);
            Path html = out.resolve("fleet.html");
            Files.writeString(html, html(now, previous));
            return html;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Snapshot latest(Path out) throws IOException {
        if (!Files.isDirectory(out)) {
            return null;
        }
        try (Stream<Path> s = Files.list(out)) {
            Optional<Path> last = s.filter(p -> p.getFileName().toString().matches("fleet-.*\\.json")).max(Comparator.naturalOrder());
            return last.isEmpty() ? null : OrderJson.MAPPER.readValue(last.get().toFile(), new TypeReference<Snapshot>() {
            });
        }
    }

    static String html(Snapshot now, Snapshot previous) {
        Map<String, Service> prev = previous == null ? Map.of()
                : previous.services().stream().collect(Collectors.toMap(Service::name, Function.identity(), (a, b) -> b));
        List<Service> services = new ArrayList<>(now.services());
        services.sort(Comparator.comparingDouble(Service::score));
        StringBuilder rows = new StringBuilder();
        for (Service s : services) {
            Service p = prev.get(s.name());
            String trend = p == null ? "<span class=muted>new</span>" : delta(s.score() - p.score());
            rows.append("<tr><td><b>").append(esc(s.name())).append("</b><div class=muted>").append(s.files()).append(" files · ")
                    .append(s.entrypoints()).append(" entrypoints</div></td>")
                    .append("<td>").append(bar(s.score())).append(" ").append(trend).append("</td>");
            for (String pack : List.of("logging", "tracing", "metrics")) {
                Double ps = s.packScore().get(pack);
                rows.append("<td class=num>").append(ps == null ? "<span class=muted>–</span>" : ps).append("</td>");
            }
            rows.append("<td class=num><span class='sev high'>").append(s.severity().getOrDefault("high", 0))
                    .append("</span> <span class='sev medium'>").append(s.severity().getOrDefault("medium", 0))
                    .append("</span> <span class='sev low'>").append(s.severity().getOrDefault("low", 0)).append("</span></td>")
                    .append("<td>").append(topRules(s.rules())).append("</td>")
                    .append("<td class=num>").append(s.overrides().isEmpty() ? "–"
                            : "<span title='" + esc(String.join("\n", s.overrides())) + "'>" + s.overrides().size() + "</span>")
                    .append("</td></tr>\n");
        }
        Map<String, Integer> fleetRules = new TreeMap<>();
        services.forEach(s -> s.rules().forEach((k, v) -> fleetRules.merge(k, v, Integer::sum)));
        StringBuilder ruleRows = new StringBuilder();
        fleetRules.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).forEach(e -> {
            long affected = services.stream().filter(s -> s.rules().containsKey(e.getKey())).count();
            ruleRows.append("<tr><td><code>").append(e.getKey()).append("</code></td><td class=muted>")
                    .append(String.join(", ", new TreeSet<>(Rules.packsOf(e.getKey())))).append("</td><td class=num>")
                    .append(e.getValue()).append("</td><td class=num>").append(affected).append(" / ").append(services.size()).append("</td></tr>\n");
        });
        return """
                <!doctype html><html lang=en><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
                <title>Logging Fleet Report</title><style>
                :root{--bg:#fbfbfa;--fg:#1d1d1b;--muted:#6b6b66;--line:#e4e3de;--card:#fff;--good:#2f7d4f;--mid:#b07a12;--bad:#b3392f}
                @media (prefers-color-scheme:dark){:root{--bg:#161615;--fg:#ecebe6;--muted:#9a9993;--line:#2c2c2a;--card:#1e1e1c;--good:#5fb985;--mid:#d9a441;--bad:#e2685d}}
                body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.5 system-ui,sans-serif}
                main{max-width:1100px;margin:0 auto;padding:24px 16px}h1{font-size:22px;margin:0 0 4px}h2{font-size:16px;margin:28px 0 8px}
                .muted{color:var(--muted);font-size:12px}table{width:100%%;border-collapse:collapse;background:var(--card);border:1px solid var(--line)}
                th,td{padding:8px 10px;border-bottom:1px solid var(--line);text-align:left;vertical-align:top}th{font-size:12px;color:var(--muted);font-weight:600}
                .num{text-align:right;font-variant-numeric:tabular-nums}.wrap{overflow-x:auto}
                .bar{display:inline-block;width:90px;height:8px;border-radius:4px;background:var(--line);vertical-align:middle;margin-right:6px}
                .bar i{display:block;height:100%%;border-radius:4px}.sev{font-variant-numeric:tabular-nums}.high{color:var(--bad)}.medium{color:var(--mid)}.low{color:var(--muted)}
                .up{color:var(--good)}.down{color:var(--bad)}code{font-size:12px}
                </style></head><body><main>
                <h1>Logging, MDC &amp; tracing compliance</h1>
                <div class=muted>%s · %d services · score = 100 × files / (files + 3·high + 1·medium + 0.25·low)</div>
                <h2>Services (lowest score first)</h2><div class=wrap><table>
                <tr><th>Service</th><th>Score · trend</th><th class=num>logging</th><th class=num>tracing</th><th class=num>metrics</th>
                <th class=num>high · med · low</th><th>Top rules</th><th class=num>Overrides</th></tr>
                %s</table></div>
                <h2>Rules across the fleet</h2><div class=wrap><table><tr><th>Rule</th><th>Packs</th><th class=num>Findings</th><th class=num>Services</th></tr>
                %s</table></div>
                <p class=muted>Generated by logast fleet. Fix locally: <code>logast scan &lt;service&gt;</code> then <code>logast apply &lt;service&gt; --auto</code>.</p>
                </main></body></html>
                """.formatted(esc(now.created()), services.size(), rows, ruleRows);
    }

    private static String bar(double score) {
        String color = score >= 90 ? "var(--good)" : score >= 70 ? "var(--mid)" : "var(--bad)";
        return "<span class=bar><i style='width:" + Math.max(2, score) + "%;background:" + color + "'></i></span><b>" + score + "</b>";
    }

    private static String delta(double d) {
        if (Math.abs(d) < 0.05) {
            return "<span class=muted>±0</span>";
        }
        return d > 0 ? "<span class=up>▲ " + round(d) + "</span>" : "<span class=down>▼ " + round(-d) + "</span>";
    }

    private static String topRules(Map<String, Integer> rules) {
        return rules.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(3)
                .map(e -> "<code>" + e.getKey() + "</code> " + e.getValue()).collect(Collectors.joining("<br>"));
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;");
    }
}
