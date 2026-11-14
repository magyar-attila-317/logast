package dev.logast.rules;

import dev.logast.Analysis;
import dev.logast.metrics.MeterCatalog;
import dev.logast.model.Entrypoint;
import dev.logast.model.LogCall;
import dev.logast.model.MethodInfo;
import dev.logast.orders.Ansi;
import dev.logast.parse.Pos;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.TypeUtils;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a service measures and what it does not, rich enough to decide without reading code: where metrics go, which
 * service/version they carry, which metric (and tag values) covers each entrypoint, the meters in code, and the
 * outcomes the logs report that no metric counts. Built for an LLM or a person to pick from; logast does not decide
 * which business metrics matter.
 */
public final class MetricsMap {
    /** @param query the exact metric and tag values to look for, e.g. {@code http.server.requests{method="POST", uri="/orders"}} */
    public record Measured(String entrypoint, String kind, String query, String by, String file, int line) {
    }

    /**
     * An outcome the logs report and no metric counts.
     *
     * @param message   the log line's text
     * @param event     what it says happened (completed, failed, skipped, closed, …)
     * @param paths     entrypoints that reach it, with their kind
     * @param frequency "low" (jobs, startup, traffic within budget) or why not
     * @param tagOption how to count it as an outcome tag on the observation that is already running, or null
     * @param counter   a counter to add instead
     */
    public record Unmeasured(String method, String file, int line, String level, String message, String event,
                             List<String> paths, String frequency, String tagOption, String counter) {
    }

    public record Map(List<String> exporters, String exportNote, java.util.Map<String, String> identity,
                      List<Measured> measured, List<String> unmeasuredEntrypoints, List<String> repositories,
                      List<MeterCatalog.Meter> meters, List<Unmeasured> outcomes,
                      @com.fasterxml.jackson.annotation.JsonIgnore List<Gap> gaps) {
    }

    /**
     * An uncounted outcome with the code it sits in, for rules that write the counting line.
     *
     * @param amounts numbers the line logs, by suffix key ([rows={}] → rows → its argument)
     */
    record Gap(Unmeasured outcome, MethodInfo method, LogCall log, String name, boolean tagAvailable,
               java.util.Map<String, org.openrewrite.java.tree.Expression> amounts) {
    }

    /** Numbers that name something rather than measure it: an id, a number, a code, a date part, a version. */
    private static final Pattern IDENTIFYING = Pattern.compile(
            "(?i).*(id|ids|number|no|nr|code|key|year|month|week|day|hour|version|port|status|type|index|idx)");

    /** The [key={}] values of a log line that are numbers and quantities: what an outcome's amount can be. */
    static java.util.Map<String, org.openrewrite.java.tree.Expression> amounts(LogCall lc, String raw) {
        java.util.Map<String, org.openrewrite.java.tree.Expression> out = new LinkedHashMap<>();
        List<String> keys = MessageTemplate.compliantKeys(raw);
        List<org.openrewrite.java.tree.Expression> args = lc.args();
        if (keys != null && args.size() == keys.size() + 1 && TypeUtils.isAssignableTo("java.lang.Throwable", args.getLast().getType())) {
            args = args.subList(0, keys.size());   // the exception after the values
        }
        if (keys == null || keys.size() != args.size()) {
            return out;
        }
        for (int i = 0; i < keys.size(); i++) {
            org.openrewrite.java.tree.JavaType t = args.get(i).getType();
            boolean number = t instanceof org.openrewrite.java.tree.JavaType.Primitive p
                    ? p != org.openrewrite.java.tree.JavaType.Primitive.Boolean && p != org.openrewrite.java.tree.JavaType.Primitive.Char
                    && p != org.openrewrite.java.tree.JavaType.Primitive.String && p != org.openrewrite.java.tree.JavaType.Primitive.Null
                    && p != org.openrewrite.java.tree.JavaType.Primitive.Void && p != org.openrewrite.java.tree.JavaType.Primitive.None
                    : TypeUtils.isAssignableTo("java.lang.Number", t);
            if (number && !IDENTIFYING.matcher(keys.get(i)).matches()) {
                out.putIfAbsent(keys.get(i), args.get(i));
            }
        }
        return out;
    }

    static final String RULE_IGNORE = "logast:ignore";

    private static final java.util.Map<String, String> EXPORTERS = new LinkedHashMap<>();

    static {
        EXPORTERS.put("io.micrometer.prometheusmetrics.PrometheusMeterRegistry", "prometheus");
        EXPORTERS.put("io.micrometer.prometheus.PrometheusMeterRegistry", "prometheus");
        EXPORTERS.put("io.micrometer.registry.otlp.OtlpMeterRegistry", "otlp");
        EXPORTERS.put("io.micrometer.newrelic.NewRelicMeterRegistry", "new-relic");
        EXPORTERS.put("io.micrometer.datadog.DatadogMeterRegistry", "datadog");
        EXPORTERS.put("io.micrometer.dynatrace.DynatraceMeterRegistry", "dynatrace");
        EXPORTERS.put("io.micrometer.cloudwatch2.CloudWatchMeterRegistry", "cloudwatch");
        EXPORTERS.put("io.micrometer.statsd.StatsdMeterRegistry", "statsd");
    }

    private static final Pattern OBSERVATION_NAME = Pattern.compile("ObservedContext\\.entrypoint\\(\"([^\"]+)\"");
    /** Past-tense outcome words at the end of a message: "Order items closed", "Invoice export skipped". */
    private static final Pattern OUTCOME = Pattern.compile("(?i)\\b(\\w+ed|sent|done|read|rejected|cancelled|built)\\s*$");
    private static final Set<String> NOT_OUTCOMES = Set.of("started", "read", "received", "called", "used", "loaded", "requested",
            "invoked", "scheduled", "queued", "enabled", "disabled", "configured", "initialized", "initialised", "registered");

    private MetricsMap() {
    }

    public static Map of(Analysis a) {
        Phrasing phrasing = new Phrasing(a.policy);
        List<String> exporters = new ArrayList<>(new LinkedHashSet<>(EXPORTERS.entrySet().stream()
                .filter(e -> a.project.probe().hasClass(e.getKey())).map(java.util.Map.Entry::getValue).toList()));
        boolean registry = a.project.probe().hasClass("io.micrometer.core.instrument.MeterRegistry");
        String note = !registry ? "Micrometer is not on the classpath: nothing is measured."
                : exporters.isEmpty() ? "No exporter on the classpath: meters are collected but never leave the application."
                : exporters.contains("prometheus") && !String.valueOf(a.springProperties.get("management.endpoints.web.exposure.include")).contains("prometheus")
                ? "Prometheus registry present, but management.endpoints.web.exposure.include does not list prometheus: nothing to scrape."
                : null;

        java.util.Map<String, String> identity = new LinkedHashMap<>();
        identity.put("spring.application.name", a.springProperties.getOrDefault("spring.application.name", "(not set)"));
        identity.put("spring.application.version", a.springProperties.getOrDefault("spring.application.version", "(not set: manifest Implementation-Version, if any)"));
        identity.put("meter tags", a.springProperties.keySet().stream().filter(k -> k.startsWith("management.metrics.tags."))
                .map(k -> k.substring("management.metrics.tags.".length())).reduce((x, y) -> x + ", " + y).orElse("(none)"));

        List<Measured> measured = new ArrayList<>();
        List<String> unmeasuredEps = new ArrayList<>();
        Set<String> observedMethods = new HashSet<>();
        for (Entrypoint ep : a.entrypoints) {
            MethodInfo m = ep.method();
            String body = m.decl().getBody() == null ? "" : m.file().slice(Pos.start(m.decl().getBody()), Pos.end(m.decl().getBody()));
            Matcher obs = OBSERVATION_NAME.matcher(body);
            String query = null;
            String by = null;
            if (obs.find()) {
                query = obs.group(1) + "{error=…}  (timer from the observation; also " + obs.group(1) + ".active)";
                by = "ObservedContext";
            } else if (ep.observedByFramework()) {
                switch (ep.kind()) {
                    case HTTP -> {
                        String[] mu = ep.label().split(" ", 2);
                        query = "http.server.requests{method=\"" + mu[0] + "\", uri=\"" + (mu.length > 1 ? mu[1] : "") + "\"}";
                        by = "Spring MVC";
                    }
                    case SCHEDULED -> {
                        query = "tasks.scheduled.execution{code.namespace=\"" + m.owner().fqn() + "\", code.function=\"" + m.name() + "\"}";
                        by = "Spring scheduling";
                    }
                    case KAFKA -> {
                        query = "spring.kafka.listener{…}  (listener " + m.label() + ")";
                        by = "Spring Kafka";
                    }
                    default -> {
                    }
                }
            }
            if (query != null) {
                measured.add(new Measured(ep.label(), ep.kind().name().toLowerCase(Locale.ROOT), query, by, m.file().relPath(), m.line()));
                observedMethods.add(m.key());
            } else {
                unmeasuredEps.add(ep.kind().name().toLowerCase(Locale.ROOT) + " " + ep.label() + "  " + m.file().relPath() + ":" + m.line());
            }
        }

        List<String> repositories = a.index.classes().stream()
                .filter(c -> c.type() != null && TypeUtils.isAssignableTo("org.springframework.data.repository.Repository", c.type())
                        && c.decl().getKind() == J.ClassDeclaration.Kind.Type.Interface)
                .map(c -> "spring.data.repository.invocations{repository=\"" + c.simpleName() + "\", method=…}")
                .sorted().toList();

        List<MeterCatalog.Meter> meters = MeterCatalog.of(a).stream().filter(mt -> !mt.source().equals("framework")).toList();
        Set<String> metered = new HashSet<>();
        for (MeterCatalog.Meter mt : meters) {
            metered.add(mt.file() + ":" + mt.line());
        }

        List<Unmeasured> outcomes = new ArrayList<>();
        List<Gap> gaps = new ArrayList<>();
        for (MethodInfo m : a.index.methods()) {
            if (m.owner().fqn().startsWith(a.supportPackage + ".") || m.facts().logs.isEmpty() || hasMeter(m, metered)) {
                continue;
            }
            String body = m.decl().getBody() == null ? "" : m.file().slice(Pos.start(m.decl().getBody()), Pos.end(m.decl().getBody()));
            if (body.contains("ObservedContext.entrypoint(") && body.contains(".quiet()")) {
                continue; // a business-event observation: counted already
            }
            for (LogCall lc : m.facts().logs) {
                if (lc.level().compareTo(dev.logast.model.Level.INFO) < 0) {
                    continue;
                }
                String raw = MessageTemplate.text(lc.template());
                if (raw == null) {
                    continue; // built at runtime: no wording to judge
                }
                String text = MessageTemplate.compliantBase(raw).strip();
                String event = phrasing.eventOf(text);
                if (event == null) {
                    Matcher o = OUTCOME.matcher(text);
                    event = o.find() ? o.group(1).toLowerCase(Locale.ROOT) : null;
                }
                if (event == null || NOT_OUTCOMES.contains(event) || text.isBlank() || countedOrSkipped(m, lc)) {
                    continue;
                }
                // a plain "completed"/"failed" of an entrypoint the framework or a wrapper times is in its timer already
                if ((event.equals("completed") || event.equals("failed")) && observedMethods.contains(m.key())) {
                    continue;
                }
                List<Entrypoint> eps = a.paths.entrypointsReaching(m);
                List<String> paths = eps.stream().map(e -> e.kind().name().toLowerCase(Locale.ROOT) + " " + e.label()).distinct().limit(5).toList();
                boolean inObservation = eps.stream().anyMatch(e -> e.observedByFramework() || observedMethods.contains(e.method().key()));
                String fromPhrasing = phrasing.subjectOf(text);
                String subject = clean(fromPhrasing != null ? fromPhrasing : subjectOf(text, event));
                String metric = metricName(subject);
                String tag = inObservation
                        ? "observationRegistry.getCurrentObservation().lowCardinalityKeyValue(\"outcome\", \"" + event + "\")  "
                        + "(tags the running " + (eps.isEmpty() ? "" : eps.getFirst().label()) + " timer; same thread only)"
                        : null;
                String counter = "Counter.builder(\"" + metric + "\").tag(\"outcome\", \"" + event + "\").register(meterRegistry).increment()";
                Unmeasured u = new Unmeasured(m.label(), m.file().relPath(), Pos.line(lc.call()), lc.level().name().toLowerCase(Locale.ROOT),
                        text, event, paths, frequency(a, eps), tag, counter);
                outcomes.add(u);
                gaps.add(new Gap(u, m, lc, metric, tag != null, amounts(lc, raw)));
            }
        }
        outcomes.sort(Comparator.comparing(Unmeasured::file).thenComparingInt(Unmeasured::line));
        gaps.sort(Comparator.comparing((Gap g) -> g.outcome().file()).thenComparingInt(g -> g.outcome().line()));
        return new Map(exporters, note, identity, measured, unmeasuredEps, repositories, meters, outcomes, gaps);
    }

    /** An Outcomes.count/tag right after the log line, or an ignore comment for this rule on it or above it. */
    private static boolean countedOrSkipped(MethodInfo m, LogCall lc) {
        int line = Pos.line(lc.call());
        int last = m.file().text().substring(0, Math.min(m.file().text().length(), Pos.end(lc.call()))).split("\n", -1).length;
        for (int l = line - 1; l <= last + 2; l++) {
            String t = l < 1 ? "" : m.file().lineText(l);
            if (t == null) {
                continue;
            }
            if (l > line - 1 && (t.contains("Outcomes.count(") || t.contains("Outcomes.record(") || t.contains("Outcomes.tag("))) {
                return true;
            }
            int i = t.indexOf(RULE_IGNORE);
            if (i >= 0 && l <= last) {
                String rest = t.substring(i + RULE_IGNORE.length()).trim();
                if (rest.isEmpty() || rest.contains(Rules.METRIC_OUTCOME_UNMEASURED)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasMeter(MethodInfo m, Set<String> metered) {
        int from = m.line();
        int to = m.decl().getBody() == null ? from : m.file().text().substring(0, Math.min(m.file().text().length(), Pos.end(m.decl().getBody())))
                .split("\n", -1).length;
        for (int l = from; l <= to; l++) {
            if (metered.contains(m.file().relPath() + ":" + l)) {
                return true;
            }
        }
        return false;
    }

    /** Drops placeholders, details after ":", parentheses and trailing "via/for/with …" clauses. */
    private static String clean(String s) {
        String c = s.replaceAll(":.*$", "").replaceAll("\\{}|\\(.*?\\)|\\[.*?]", " ")
                .replaceAll("(?i)\\s+(via|for|with|from|in|on|at|because|due)\\b.*$", "").replaceAll("[^\\w ]", " ").strip();
        return c.isEmpty() ? "outcome" : c;
    }

    /** "Order items closed" → "Order items"; "Failed to broadcast X via SSE: {}" → "broadcast X". */
    private static String subjectOf(String text, String event) {
        String lower = text.toLowerCase(Locale.ROOT);
        String s;
        if (lower.startsWith(event + " to ")) {
            s = text.substring(event.length() + 4).replaceAll("(?i)\\s+(via|for|with|from|in|on|at|because)\\b.*$", "");
        } else {
            int at = lower.lastIndexOf(event);
            s = at > 0 ? text.substring(0, at) : text;
        }
        s = s.replaceAll("\\{}|\\(.*?\\)|:.*$", " ").replaceAll("[^\\w ]", " ").strip();
        return s.isEmpty() ? "outcome" : s;
    }

    /** "Order items" → "order.items". */
    static String metricName(String subject) {
        String n = subject.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", ".").replaceAll("^\\.|\\.$", "");
        return n.isEmpty() ? "outcome" : n;
    }

    /** "low" when every path is a job/startup or within the traffic budget, else why not. */
    static String frequency(Analysis a, List<Entrypoint> eps) {
        if (eps.isEmpty()) {
            return "unknown: no entrypoint is known to reach it";
        }
        double budget = a.policy.levels().volumeBudget();
        for (Entrypoint ep : eps) {
            if (lowFrequency(ep)) {
                continue;
            }
            Double rate = a.traffic.known() ? a.traffic.perDay(ep.label()) : null;
            if (rate == null) {
                return "unknown: no rate for \"" + ep.label() + "\" in .logast/traffic.yaml";
            }
            if (rate > budget) {
                return "high: " + ep.label() + " " + VolumeRules.human(rate) + "/day";
            }
        }
        return "low";
    }

    private static final Pattern STARTUP_EVENT = Pattern.compile("\\((Application(Ready|Started|Prepared)Event|ContextRefreshedEvent|WebServerInitializedEvent)\\)");

    /** Runs a handful of times a day by its nature: jobs, batch runners, main, and listeners of startup events. */
    static boolean lowFrequency(Entrypoint ep) {
        return ep.kind() == Entrypoint.Kind.SCHEDULED || ep.kind() == Entrypoint.Kind.BATCH || ep.kind() == Entrypoint.Kind.MAIN
                || ep.kind() == Entrypoint.Kind.EVENT && STARTUP_EVENT.matcher(ep.label()).find();
    }

    /** Plain text: counts and examples; {@code --format json} has every entry with its snippets. */
    public static String text(Map m) {
        StringBuilder sb = new StringBuilder();
        sb.append(Ansi.bold("Export")).append(": ").append(m.exporters().isEmpty() ? "none" : String.join(", ", m.exporters())).append('\n');
        if (m.exportNote() != null) {
            sb.append(Ansi.warn("  " + m.exportNote())).append('\n');
        }
        sb.append(Ansi.bold("Identity")).append('\n');
        m.identity().forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append('\n'));

        sb.append('\n').append(Ansi.bold("Measured without code: " + m.measured().size() + " entrypoints")).append('\n');
        java.util.Map<String, List<Measured>> byKind = new TreeMap<>();
        m.measured().forEach(x -> byKind.computeIfAbsent(x.by(), k -> new ArrayList<>()).add(x));
        byKind.forEach((by, xs) -> {
            sb.append("  ").append(xs.size()).append(" by ").append(by).append(", e.g. ").append(xs.getFirst().query()).append('\n');
        });
        if (!m.repositories().isEmpty()) {
            sb.append("  ").append(m.repositories().size()).append(" Spring Data repositories, e.g. ").append(m.repositories().getFirst()).append('\n');
        }

        sb.append('\n').append(Ansi.bold("Entrypoints no metric covers: " + m.unmeasuredEntrypoints().size())).append('\n');
        java.util.Map<String, List<String>> eps = new TreeMap<>();
        m.unmeasuredEntrypoints().forEach(e -> eps.computeIfAbsent(e.substring(0, e.indexOf(' ')), k -> new ArrayList<>()).add(e.substring(e.indexOf(' ') + 1)));
        eps.forEach((kind, xs) -> {
            sb.append("  ").append(kind).append(" (").append(xs.size()).append(")\n");
            xs.stream().limit(5).forEach(x -> sb.append("    ").append(x).append('\n'));
            if (xs.size() > 5) {
                sb.append(Ansi.dim("    … " + (xs.size() - 5) + " more (--format json)")).append('\n');
            }
        });

        sb.append('\n').append(Ansi.bold("Meters in code: " + m.meters().size()))
                .append(Ansi.dim(m.meters().isEmpty() ? "" : "   (logast metrics . --format table lists them)")).append('\n');

        java.util.Map<String, Long> byEvent = new TreeMap<>();
        m.outcomes().forEach(u -> byEvent.merge(u.event(), 1L, Long::sum));
        sb.append('\n').append(Ansi.bold("Outcomes logged but not counted: " + m.outcomes().size())).append("  ")
                .append(Ansi.dim(byEvent.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue(), x.getValue()))
                        .map(e -> e.getKey() + " " + e.getValue()).reduce((x, y) -> x + ", " + y).orElse(""))).append('\n')
                .append(Ansi.dim("  pick the ones a dashboard or alert needs; snippets (tag or counter) in --format json and in the "
                        + "METRIC_OUTCOME_UNMEASURED findings")).append('\n');
        for (Unmeasured u : m.outcomes()) {
            sb.append("  ").append(u.file()).append(':').append(u.line()).append("  ").append(u.method()).append('\n')
                    .append("    ").append(u.level().toUpperCase(Locale.ROOT)).append(" \"").append(u.message()).append("\"  outcome=")
                    .append(u.event()).append("  frequency ").append(u.frequency()).append('\n');
            if (!u.paths().isEmpty()) {
                sb.append(Ansi.dim("    from " + String.join("; ", u.paths()))).append('\n');
            }
        }
        return sb.toString();
    }
}
