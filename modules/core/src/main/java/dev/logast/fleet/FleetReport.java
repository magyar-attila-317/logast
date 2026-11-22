package dev.logast.fleet;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.logast.orders.OrderJson;
import dev.logast.orders.ScanResult;
import dev.logast.orders.WorkOrder;
import dev.logast.rules.RuleHelp;
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
                          List<String> overrides, List<dev.logast.metrics.MeterCatalog.Meter> meters, Double logCoverage,
                          ServiceSurface surface) {
        public Service(String name, String root, int files, int entrypoints, Map<String, Integer> severity,
                       Map<String, Integer> rules, Map<String, Double> packScore, double score,
                       List<String> overrides, List<dev.logast.metrics.MeterCatalog.Meter> meters) {
            this(name, root, files, entrypoints, severity, rules, packScore, score, overrides, meters, null, null);
        }

        public Service {
            meters = meters == null ? List.of() : List.copyOf(meters);   // snapshots written before meters existed
        }
    }

    /** @param links what only the comparison of the services shows (#113); null in snapshots written before it */
    public record Snapshot(String created, List<Service> services, FleetLinks.Result links) {
    }

    private FleetReport() {
    }

    /** @param packs selected packs; unselected ones get no score (shown as "–") */
    public static Service service(String name, ScanResult r, List<String> overrides, List<String> packs) {
        return service(name, r, overrides, packs, List.of());
    }

    public static Service service(String name, ScanResult r, List<String> overrides, List<String> packs,
                                  List<dev.logast.metrics.MeterCatalog.Meter> meters) {
        return service(name, r, overrides, packs, meters, null);
    }

    /** @param surface its calls, entrypoints and counted outcomes, for comparing it with the other services (#113) */
    public static Service service(String name, ScanResult r, List<String> overrides, List<String> packs,
                                  List<dev.logast.metrics.MeterCatalog.Meter> meters, ServiceSurface surface) {
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
                round(100.0 * files / (files + total)), overrides, meters,
                r.coverage() == null || r.coverage().total() == 0 ? null : r.coverage().percent(), surface);
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
        return write(out, services, FleetLinks.of(services));
    }

    /** As {@link #write(Path, List)}, with the comparison of the services already made. */
    public static Path write(Path out, List<Service> services, FleetLinks.Result links) {
        return write(out, services, links, List.of());
    }

    /** As {@link #write(Path, List, FleetLinks.Result)}, with company.yaml's suggestions of a named fleet (#129). */
    public static Path write(Path out, List<Service> services, FleetLinks.Result links, List<String> company) {
        try {
            Files.createDirectories(out);
            Snapshot previous = latest(out);
            Snapshot now = new Snapshot(Instant.now().toString(), services, links);
            OrderJson.MAPPER.writeValue(out.resolve("fleet-" + now.created().replace(':', '-') + ".json").toFile(), now);
            Path html = out.resolve("fleet.html");
            Files.writeString(html, html(now, previous, company));
            return html;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * #141: as {@link #write(Path, List, FleetLinks.Result, List)} for a page rewritten by every overview and member scan:
     * one history file per day ({@code fleet-<date>.json}, rewritten during the day), the trend against the last
     * earlier day.
     */
    public static Path writeDaily(Path out, List<Service> services, FleetLinks.Result links, List<String> company) {
        return writeDaily(out, services, links, company, null);
    }

    /** As {@link #writeDaily(Path, List, FleetLinks.Result, List)}, with the fleet's next steps on top (#149; null: none). */
    public static Path writeDaily(Path out, List<Service> services, FleetLinks.Result links, List<String> company,
                                  List<String> next) {
        try {
            Files.createDirectories(out);
            Snapshot now = new Snapshot(Instant.now().toString(), services, links);
            String today = "fleet-" + now.created().substring(0, 10);
            Snapshot previous = latest(out, today);
            OrderJson.MAPPER.writeValue(out.resolve(today + ".json").toFile(), now);
            Path html = out.resolve("fleet.html");
            Files.writeString(html, html(now, previous, company, next));
            return html;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Snapshot latest(Path out) throws IOException {
        return latest(out, null);
    }

    /** The newest history file, or the newest one named before {@code before} (an earlier day). */
    static Snapshot latest(Path out, String before) throws IOException {
        if (!Files.isDirectory(out)) {
            return null;
        }
        try (Stream<Path> s = Files.list(out)) {
            Optional<Path> last = s.filter(p -> p.getFileName().toString().matches("fleet-.*\\.json"))
                    .filter(p -> before == null || p.getFileName().toString().compareTo(before) < 0).max(Comparator.naturalOrder());
            return last.isEmpty() ? null : OrderJson.MAPPER.readValue(last.get().toFile(), new TypeReference<Snapshot>() {
            });
        }
    }

    static String html(Snapshot now, Snapshot previous) {
        return html(now, previous, List.of());
    }

    static String html(Snapshot now, Snapshot previous, List<String> company) {
        return html(now, previous, company, null);
    }

    /** #149: the steps as a box on top; a command or file in backticks becomes code. */
    static String nextBox(List<String> next) {
        if (next == null) {
            return "";
        }
        if (next.isEmpty()) {
            return "<div class=next><b>Next steps</b><div class=muted>Nothing left: the members follow the company policy and "
                    + "nothing high is open.</div></div>\n";
        }
        StringBuilder out = new StringBuilder("<div class=next><b>Next steps</b><ol>\n");
        for (String step : next) {
            String[] parts = esc(step).split("`", -1);
            out.append("<li>");
            for (int i = 0; i < parts.length; i++) {
                out.append(i % 2 == 1 ? "<code>" + parts[i] + "</code>" : parts[i]);
            }
            out.append("</li>\n");
        }
        return out.append("</ol></div>\n").toString();
    }

    static String html(Snapshot now, Snapshot previous, List<String> company, List<String> next) {
        Map<String, Service> prev = previous == null ? Map.of()
                : previous.services().stream().collect(Collectors.toMap(Service::name, Function.identity(), (a, b) -> b));
        List<Service> services = new ArrayList<>(now.services());
        services.sort(Comparator.comparingDouble(Service::score));
        StringBuilder rows = new StringBuilder();
        for (Service s : services) {
            Service p = prev.get(s.name());
            String trend = p == null ? "<span class=muted>new</span>" : delta(s.score() - p.score());
            rows.append("<tr class=svc data-name='").append(esc(s.name())).append("' title='Click for this service&#39;s breakdown'><td><b>")
                    .append(esc(s.name())).append("</b><div class=muted>").append(s.files()).append(" files · ")
                    .append(s.entrypoints()).append(" entrypoints</div></td>")
                    .append("<td>").append(bar(s.score())).append(" ").append(trend).append("</td>");
            rows.append("<td class=num>").append(s.logCoverage() == null ? "<span class=muted>–</span>" : s.logCoverage() + "%").append("</td>");
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
            ruleRows.append("<tr><td>").append(rule(e.getKey())).append("</td><td class=muted>")
                    .append(String.join(", ", new TreeSet<>(Rules.packsOf(e.getKey())))).append("</td><td class=num>")
                    .append(e.getValue()).append("</td><td class=num>").append(affected).append(" / ").append(services.size()).append("</td></tr>\n");
        });
        String meterRows = meterRows(services);
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
                %s</style></head><body><main>
                <h1>Logging, MDC &amp; tracing compliance</h1>
                <div class=muted>%s · %d services · score = 100 × files / (files + 3·high + 1·medium + 0.25·low)</div>
                %s
                <h2>Services (lowest score first)</h2><div class='wrap scroll'><table>
                <tr><th>Service</th><th>Score · trend</th><th class=num>log coverage</th><th class=num>logging</th><th class=num>tracing</th><th class=num>metrics</th>
                <th class=num>high · med · low</th><th>Top rules</th><th class=num>Overrides</th></tr>
                %s</table></div>
                <h2>Rules across the fleet</h2><div class='wrap scroll'><table><tr><th>Rule</th><th>Packs</th><th class=num>Findings</th><th class=num>Services</th></tr>
                %s</table></div>
                %s<h2>Meters across services</h2>
                <div class=muted>Same name with different types or tag keys cannot be compared on one dashboard; similar
                names measure the same thing under different names.</div><div class='wrap scroll'><table>
                <tr><th>Meter</th><th>Services</th><th>Finding</th></tr>
                %s</table></div>
                <h2>Links between services</h2>
                %s<h2>Trace across services</h2>
                <div class=muted>A call of one service that reaches an entrypoint of another (same path and method, same topic
                or queue), and the side that drops the trace. The host of the call's base URL decides between services
                with the same path: confirmed (the host names the service), path only (one service has the path, the
                host is not known), ambiguous (several have it: one row with the candidates).</div><div class='wrap scroll'><table class=fixed>
                <tr><th>Call</th><th>Entrypoint</th><th>Trace</th></tr>
                %s</table></div>
                %s
                <h2>One outcome, several metric names</h2>
                <div class=muted>The same logged outcome counted under different meters in different services: candidates to
                agree on one name. Nothing is merged.</div><div class='wrap scroll'><table>
                <tr><th>Outcome</th><th>Meters</th><th>Where</th></tr>
                %s</table></div>
                %s<p class=muted>Generated by logast fleet. Fix locally: <code>logast scan &lt;service&gt;</code> then <code>logast apply &lt;service&gt;</code>.</p>
                </main></body></html>
                """.formatted(FleetGraph.CSS + CSS, esc(now.created()), services.size(), nextBox(next), rows, ruleRows, companyRows(company), meterRows,
                FleetGraph.svg(services.stream().map(Service::name).toList(), now.links()), traceRows(now.links()), otherHostRows(now.links()), metricNameRows(now.links()), drawer(services, now.links()));
    }

    /** #129: the settings company.yaml could take from its members (commented suggestions there). */
    static String companyRows(List<String> company) {
        if (company == null || company.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("<h2>Company policy: what the members agree on</h2>\n<div class=muted>Commented "
                + "suggestions in the fleet's company.yaml: uncomment one to make it every member's setting.</div><ul>\n");
        company.forEach(c -> out.append("<li><code>").append(esc(c)).append("</code></li>\n"));
        return out.append("</ul>\n").toString();
    }

    /** #113: broken pairs first, then the ones that carry the trace on. */
    static String traceRows(FleetLinks.Result links) {
        if (links == null || links.trace().isEmpty()) {
            return "<tr><td colspan=3 class=muted>No call reaches an entrypoint of another service.</td></tr>";
        }
        StringBuilder out = new StringBuilder();
        List<FleetLinks.Trace> sorted = new ArrayList<>(links.trace());
        sorted.sort(Comparator.comparing((FleetLinks.Trace t) -> t.breaks().isEmpty()));
        for (FleetLinks.Trace t : sorted) {
            StringBuilder verdict = new StringBuilder();
            if (t.breaks().isEmpty()) {
                verdict.append("<span class=up>goes on</span>");
            }
            if (t.call().breaks() != null) {
                verdict.append("<span class=down>breaks at ").append(esc(t.caller())).append("</span>: ")
                        .append(esc(t.call().breaks().what())).append("<div class=muted>fix: ")
                        .append(esc(FleetLinks.fix(t.call().breaks(), t.caller()))).append("</div>");
            }
            if (t.entry() != null && t.entry().breaks() != null) {
                verdict.append("<span class=down>breaks at ").append(esc(t.callee())).append("</span>: ")
                        .append(esc(t.entry().breaks().what())).append("<div class=muted>fix: ")
                        .append(esc(FleetLinks.fix(t.entry().breaks(), t.callee()))).append("</div>");
            }
            for (FleetLinks.Candidate c : t.candidates()) {
                if (c.breaks()) {
                    verdict.append("<span class=").append(t.breaks().contains("callee") ? "down" : "medium").append(">breaks at ")
                            .append(esc(c.service())).append(" if it goes there</span>: ").append(esc(c.entry().breaks().what()))
                            .append("<div class=muted>fix: ").append(esc(FleetLinks.fix(c.entry().breaks(), c.service()))).append("</div>");
                }
            }
            if (t.note() != null) {
                verdict.append("<div class=muted>").append(esc(t.note())).append("</div>");
            }
            out.append("<tr><td>").append(call(t)).append("</td><td>").append(entries(t)).append("</td><td>").append(verdict).append("</td></tr>\n");
        }
        return out.toString();
    }

    private static String call(FleetLinks.Trace t) {
        return "<b>" + esc(t.caller()) + "</b> <code>" + esc(FleetLinks.target(t.call())) + "</code><div class=muted>"
                + esc(t.call().client()) + " · " + esc(t.call().file() + ":" + t.call().line())
                + (t.call().base() == null ? "" : " · " + esc(t.call().base())) + "</div>";
    }

    /** The callee's entrypoint, or one line per candidate of an ambiguous (or other-host) call. */
    private static String entries(FleetLinks.Trace t) {
        if (t.entry() != null) {
            return "<b>" + esc(t.callee()) + "</b> <code>" + esc(t.entry().label()) + "</code><div class=muted>"
                    + esc(t.entry().file() + ":" + t.entry().line()) + "</div>";
        }
        return t.candidates().stream().map(c -> "<b>" + esc(c.service()) + "</b>? <code>" + esc(c.entry().label())
                + "</code><div class=muted>" + esc(c.entry().file() + ":" + c.entry().line()) + "</div>").collect(Collectors.joining());
    }

    /** #128: calls whose path another service has while their host names a different one: not links. */
    static String otherHostRows(FleetLinks.Result links) {
        if (links == null || links.otherHost().isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("<h2>Same path, other host</h2>\n<div class=muted>The path is another service's, "
                + "the host of the base URL names a different one or none of the fleet: not counted as a link. A stale URL, or a "
                + "service outside the fleet.</div><div class='wrap scroll'><table class=fixed>\n<tr><th>Call</th><th>Has the path</th><th>Host</th></tr>\n");
        for (FleetLinks.Trace t : links.otherHost()) {
            out.append("<tr><td>").append(call(t)).append("</td><td>").append(entries(t)).append("</td><td>")
                    .append(esc(t.note())).append("</td></tr>\n");
        }
        return out.append("</table></div>").toString();
    }

    static String metricNameRows(FleetLinks.Result links) {
        if (links == null || links.metrics().isEmpty()) {
            return "<tr><td colspan=3 class=muted>No outcome is counted under different names.</td></tr>";
        }
        StringBuilder out = new StringBuilder();
        for (FleetLinks.MetricNames mn : links.metrics()) {
            out.append("<tr><td>").append(esc(mn.outcome())).append("</td><td>")
                    .append(mn.uses().stream().map(u -> "<code>" + esc(u.meter()) + "</code> " + esc(u.service())).distinct()
                            .collect(Collectors.joining("<br>")))
                    .append("</td><td>").append(mn.uses().stream().map(u -> esc(u.service() + " " + u.file() + ":" + u.line()
                            + " \"" + u.message() + "\" (" + u.how() + ")")).collect(Collectors.joining("<br>")))
                    .append("</td></tr>\n");
        }
        return out.toString();
    }

    /** Meters shared by services (consistent or not) and names that differ only in spelling. */
    public static String meterRows(List<Service> services) {
        Map<String, Map<String, Set<String>>> byName = new TreeMap<>();   // name -> service -> "type [keys]"
        Map<String, Set<String>> similar = new TreeMap<>();                // normalized -> names
        for (Service s : services) {
            for (var m : s.meters()) {
                if (m.source().equals("framework")) {
                    continue;
                }
                byName.computeIfAbsent(m.name(), k -> new TreeMap<>()).computeIfAbsent(s.name(), k -> new TreeSet<>())
                        .add(m.type() + " " + new TreeSet<>(m.tags()));
                similar.computeIfAbsent(normalize(m.name()), k -> new TreeSet<>()).add(m.name());
            }
        }
        StringBuilder out = new StringBuilder();
        int[] n = {0};
        byName.forEach((name, perService) -> {
            if (perService.size() < 2 || n[0] >= 60) {
                return;
            }
            Set<String> shapes = new TreeSet<>();
            perService.values().forEach(shapes::addAll);
            String finding = shapes.size() == 1 ? "<span class=up>consistent</span>"
                    : "<span class=down>differs:</span> " + esc(perService.entrySet().stream()
                    .map(e -> e.getKey() + " " + String.join(" | ", e.getValue())).collect(Collectors.joining("; ")));
            out.append("<tr><td><code>").append(esc(name)).append("</code></td><td>").append(esc(String.join(", ", perService.keySet())))
                    .append("</td><td>").append(finding).append("</td></tr>\n");
            n[0]++;
        });
        similar.values().stream().filter(names -> names.size() > 1).limit(40).forEach(names -> {
            Set<String> where = new TreeSet<>();
            names.forEach(nm -> where.addAll(byName.getOrDefault(nm, Map.of()).keySet()));
            out.append("<tr><td><code>").append(esc(String.join("</code> · <code>", names))).append("</code></td><td>")
                    .append(esc(String.join(", ", where))).append("</td><td><span class=medium>similar names</span>: pick one</td></tr>\n");
        });
        return out.isEmpty() ? "<tr><td colspan=3 class=muted>No meter is shared by two services.</td></tr>" : out.toString();
    }

    /** orders.placed / order_placed / ordersPlaced → "orderplaced". */
    public static String normalize(String name) {
        StringBuilder sb = new StringBuilder();
        for (String w : name.replaceAll("([a-z0-9])([A-Z])", "$1.$2").toLowerCase(Locale.ROOT).split("[._\\-]+")) {
            sb.append(w.length() > 3 && w.endsWith("s") && !w.endsWith("ss") ? w.substring(0, w.length() - 1) : w);
        }
        return sb.toString();
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
                .map(e -> rule(e.getKey()) + " <b>" + e.getValue() + "</b>").collect(Collectors.joining("<br>"));
    }

    /** #142: a rule id with its plain label; hovering it shows what it finds and how it is fixed. */
    static String rule(String id) {
        RuleHelp.Help h = RuleHelp.of(id);
        return "<span class=rule data-rule='" + esc(id) + "'><code>" + esc(id) + "</code>"
                + (h.label() == null ? "" : " <span class=lbl>" + esc(h.label()) + "</span>") + "</span>";
    }

    /** #142, #144: the services, their rules' help and the links as data, the drawer, and the script that uses them. */
    static String drawer(List<Service> services, FleetLinks.Result links) {
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, RuleHelp.Help> help = new TreeMap<>();
        List<Map<String, Object>> svc = new ArrayList<>();
        for (Service s : services) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name());
            m.put("root", s.root());
            m.put("files", s.files());
            m.put("entrypoints", s.entrypoints());
            m.put("score", s.score());
            m.put("packScore", s.packScore());
            m.put("severity", s.severity());
            m.put("rules", s.rules());
            m.put("overrides", s.overrides());
            m.put("logCoverage", s.logCoverage());
            m.put("meters", s.meters().stream().filter(x -> !"framework".equals(x.source())).map(x -> x.name()).distinct().sorted().toList());
            svc.add(m);
            s.rules().keySet().forEach(r -> help.put(r, RuleHelp.of(r)));
        }
        List<Map<String, Object>> edges = new ArrayList<>();
        if (links != null) {
            for (FleetLinks.Trace t : links.trace()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("caller", t.caller());
                m.put("callees", t.callee() != null ? List.of(t.callee())
                        : t.candidates().stream().map(FleetLinks.Candidate::service).distinct().toList());
                m.put("target", FleetLinks.target(t.call()));
                m.put("match", t.match());
                m.put("breaks", t.breaks().isEmpty() ? null
                        : (t.call().breaks() != null ? t.call().breaks().what() : t.entry() != null && t.entry().breaks() != null
                        ? t.entry().breaks().what() : "at the callee"));
                m.put("breaksAt", t.breaks());
                edges.add(m);
            }
        }
        data.put("services", svc);
        data.put("help", help);
        data.put("links", edges);
        String json;
        try {
            json = OrderJson.MAPPER.writer().without(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT).writeValueAsString(data).replace("</", "<\\/");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return "<aside id=drawer aria-hidden=true><button class=close aria-label=Close>×</button><div class=body></div></aside>\n"
                + "<script type=application/json id=fleet-data>" + json + "</script>\n<script>" + SCRIPT + "</script>\n";
    }

    static final String CSS = """
            main{max-width:1400px}
            .next{margin:16px 0 4px;padding:12px 16px;border:1px solid var(--line);border-left:4px solid var(--mid);border-radius:8px;
              background:var(--card)}.next ol{margin:6px 0 0;padding-left:20px}.next li{margin:3px 0}
            .scroll{max-height:520px;overflow:auto;border:1px solid var(--line);border-radius:8px;scrollbar-width:thin;
              scrollbar-color:var(--muted) transparent}
            .scroll table{border:0}.scroll th{position:sticky;top:0;background:var(--card);z-index:1;box-shadow:0 1px 0 var(--line)}
            .scroll::-webkit-scrollbar{width:10px;height:10px}.scroll::-webkit-scrollbar-track{background:transparent}
            .scroll::-webkit-scrollbar-thumb{background:var(--line);border-radius:10px;border:2px solid var(--card)}
            .scroll::-webkit-scrollbar-thumb:hover{background:var(--muted)}
            td{overflow-wrap:anywhere}table.fixed{table-layout:fixed}table.fixed th:nth-child(1),table.fixed th:nth-child(2){width:32%}
            .rule{cursor:help;white-space:normal}.rule .lbl{color:var(--muted);font-size:12px}
            tr.svc{cursor:pointer}tr.svc:hover td{background:color-mix(in srgb,var(--fg) 5%,transparent)}
            .tip{position:fixed;display:none;max-width:420px;z-index:30;background:var(--card);color:var(--fg);border:1px solid var(--line);
              border-radius:8px;padding:10px 12px;box-shadow:0 8px 24px rgba(0,0,0,.25);font-size:12px;line-height:1.45;pointer-events:none}
            .tip b{display:block;font-size:13px;margin-bottom:4px}.tip div{margin-top:4px}
            #drawer{position:fixed;top:0;right:0;height:100vh;width:min(560px,100vw);background:var(--card);border-left:1px solid var(--line);
              box-shadow:-12px 0 32px rgba(0,0,0,.25);transform:translateX(105%);transition:transform .2s ease;z-index:20;overflow:auto;
              scrollbar-width:thin;scrollbar-color:var(--muted) transparent}
            #drawer.open{transform:none}#drawer .body{padding:20px 22px 40px}#drawer h3{margin:0 0 2px;font-size:20px}
            #drawer h4{font-size:12px;text-transform:uppercase;letter-spacing:.04em;color:var(--muted);margin:22px 0 6px}
            #drawer .close{position:sticky;top:8px;float:right;margin:8px 10px 0 0;border:0;background:none;color:var(--fg);font-size:26px;
              cursor:pointer;line-height:1}
            #drawer .chips span{display:inline-block;border:1px solid var(--line);border-radius:12px;padding:1px 9px;margin:0 6px 6px 0;font-size:12px}
            #drawer .item{padding:7px 0;border-bottom:1px solid var(--line)}#drawer .item:last-child{border-bottom:0}
            #drawer pre{background:var(--bg);border:1px solid var(--line);border-radius:6px;padding:8px 10px;white-space:pre-wrap;font-size:12px}
            """;

    static final String SCRIPT = """
            (function () {
              var data = JSON.parse(document.getElementById('fleet-data').textContent), help = data.help;
              function el(parent, tag, text, cls) {
                var e = document.createElement(tag);
                if (text != null) e.textContent = text;
                if (cls) e.className = cls;
                parent.appendChild(e);
                return e;
              }
              var tip = el(document.body, 'div', null, 'tip');
              document.addEventListener('mouseover', function (ev) {
                var r = ev.target.closest('[data-rule]');
                if (!r) { tip.style.display = 'none'; return; }
                var h = help[r.dataset.rule];
                tip.textContent = '';
                el(tip, 'b', (h && h.label) || r.dataset.rule);
                el(tip, 'div', r.dataset.rule + (h && h.severity ? ' · ' + h.severity + ' severity' : ''), 'muted');
                if (h && h.finds) el(tip, 'div', 'Finds: ' + h.finds);
                if (h && h.fix) el(tip, 'div', 'Fix: ' + h.fix);
                tip.style.display = 'block';
                var b = r.getBoundingClientRect(), x = Math.min(b.left, innerWidth - tip.offsetWidth - 12), y = b.bottom + 6;
                if (y + tip.offsetHeight > innerHeight) y = b.top - tip.offsetHeight - 6;
                tip.style.left = Math.max(8, x) + 'px';
                tip.style.top = Math.max(8, y) + 'px';
              });
              var drawer = document.getElementById('drawer'), body = drawer.querySelector('.body');
              function close() { drawer.classList.remove('open'); drawer.setAttribute('aria-hidden', 'true'); }
              drawer.querySelector('.close').addEventListener('click', close);
              document.addEventListener('keydown', function (e) { if (e.key === 'Escape') close(); });
              window.openService = function (name) {
                var s = data.services.filter(function (x) { return x.name === name; })[0];
                if (!s) return;
                body.textContent = '';
                el(body, 'h3', s.name);
                el(body, 'div', s.root || '', 'muted');
                el(body, 'div', 'score ' + s.score + ' · ' + s.files + ' files · ' + s.entrypoints + ' entrypoints'
                  + (s.logCoverage == null ? '' : ' · log coverage ' + s.logCoverage + '%'), 'muted');
                var chips = el(body, 'div', null, 'chips');
                chips.style.marginTop = '10px';
                Object.keys(s.packScore || {}).forEach(function (p) { el(chips, 'span', p + ' ' + s.packScore[p]); });
                ['high', 'medium', 'low'].forEach(function (k) { el(chips, 'span', (s.severity[k] || 0) + ' ' + k, k); });
                var rules = Object.keys(s.rules).sort(function (a, b) { return s.rules[b] - s.rules[a]; });
                el(body, 'h4', 'Findings (' + rules.length + ' rules)');
                rules.forEach(function (r) {
                  var h = help[r] || {}, it = el(body, 'div', null, 'item');
                  var head = el(it, 'div');
                  el(head, 'b', s.rules[r] + '× ');
                  var span = el(head, 'span', null, 'rule');
                  span.dataset.rule = r;
                  el(span, 'span', h.label || r);
                  el(span, 'span', '  ' + r + (h.severity ? ' · ' + h.severity : ''), 'muted');
                  if (h.fix) el(it, 'div', h.fix, 'muted');
                });
                var out = data.links.filter(function (l) { return l.caller === s.name; });
                var inc = data.links.filter(function (l) { return l.callees.indexOf(s.name) >= 0; });
                function link(l, other) {
                  var it = el(body, 'div', null, 'item');
                  el(it, 'b', other + '  ');
                  el(it, 'code', l.target);
                  el(it, 'div', l.breaks ? 'trace breaks at ' + l.breaksAt.join(' and ') + ': ' + l.breaks : 'trace goes on',
                    l.breaks ? 'down' : 'up');
                }
                el(body, 'h4', 'Calls (' + out.length + ')');
                if (!out.length) el(body, 'div', 'No call reaches another service.', 'muted');
                out.forEach(function (l) { link(l, '→ ' + l.callees.join(' or ')); });
                el(body, 'h4', 'Called by (' + inc.length + ')');
                if (!inc.length) el(body, 'div', 'No other service calls it.', 'muted');
                inc.forEach(function (l) { link(l, '← ' + l.caller); });
                if (s.overrides.length) {
                  el(body, 'h4', 'Own settings (' + s.overrides.length + ')');
                  s.overrides.forEach(function (o) { el(body, 'div', o, 'item'); });
                }
                if (s.meters.length) {
                  el(body, 'h4', 'Meters (' + s.meters.length + ')');
                  var m = el(body, 'div', null, 'chips');
                  s.meters.forEach(function (x) { el(m, 'span', x); });
                }
                el(body, 'h4', 'Fix it');
                el(body, 'pre', 'cd ' + (s.root || '<' + s.name + '>') + '\\nlogast apply .');
                drawer.classList.add('open');
                drawer.setAttribute('aria-hidden', 'false');
                drawer.scrollTop = 0;
              };
              document.querySelectorAll('tr.svc').forEach(function (tr) {
                tr.addEventListener('click', function () { openService(tr.dataset.name); });
              });
            })();
            """;

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;");
    }
}
