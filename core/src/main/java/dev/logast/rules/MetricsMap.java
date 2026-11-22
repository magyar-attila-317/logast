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

    /** {@code call} sits in a lambda of a method that returns a Mono/Flux: an operator callback of the pipeline. */
    static boolean inReactiveOperator(MethodInfo m, org.openrewrite.java.tree.J call) {
        if (m.decl().getBody() == null || m.decl().getMethodType() == null
                || !TypeUtils.isAssignableTo("org.reactivestreams.Publisher", m.decl().getMethodType().getReturnType())) {
            return false;
        }
        boolean[] found = {false};
        new org.openrewrite.java.JavaIsoVisitor<Integer>() {
            @Override
            public org.openrewrite.java.tree.J.Lambda visitLambda(org.openrewrite.java.tree.J.Lambda l, Integer p) {
                new org.openrewrite.java.JavaIsoVisitor<Integer>() {
                    @Override
                    public org.openrewrite.java.tree.J.MethodInvocation visitMethodInvocation(org.openrewrite.java.tree.J.MethodInvocation mi,
                                                                                           Integer q) {
                        found[0] |= mi == call;
                        return super.visitMethodInvocation(mi, q);
                    }
                }.visit(l.getBody(), 0);
                return l;
            }
        }.visit(m.decl().getBody(), 0);
        return found[0];
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

    /** The map as the service stands: {@code @Observed} counts while the property is set and aspectjweaver is on the classpath. */
    public static Map of(Analysis a) {
        return of(a, "true".equals(a.springProperties.get("management.observations.annotations.enabled"))
                && a.project.probe().hasClass("org.aspectj.weaver.Advice"));
    }

    /**
     * @param observedAnnotationsActive whether Spring Boot processes {@code @Observed} (or an order of this scan makes it
     *                                  do so): only then is an annotated method timed and counted by its observation
     */
    public static Map of(Analysis a, boolean observedAnnotationsActive) {
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

        // @Observed methods (#19): their observation times and counts every call, with the error, like an observed entrypoint.
        // MeterCatalog records the annotation's line, which hasMeter (from the method name down) never reaches
        if (observedAnnotationsActive) {
            for (MethodInfo m : a.index.methods()) {
                if (!m.owner().fqn().startsWith(a.supportPackage + ".") && TracingRules.observedAnnotation(m, false)) {
                    observedMethods.add(m.key());
                }
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
        Set<String> catalogs = CatalogRules.catalogNames(a);   // OrderMetrics.ordersShipped() counts too
        for (MethodInfo m : a.index.methods()) {
            if (m.owner().fqn().startsWith(a.supportPackage + ".") || m.facts().logs.isEmpty() || hasMeter(m, metered)) {
                continue;
            }
            String body = m.decl().getBody() == null ? "" : m.file().slice(Pos.start(m.decl().getBody()), Pos.end(m.decl().getBody()));
            if (body.contains("ObservedContext.entrypoint(") && body.contains(".quiet()")) {
                continue; // a business-event observation: counted already
            }
            for (LogCall lc : m.facts().logs) {
                String raw = MessageTemplate.text(lc.template());
                if (raw == null) {
                    continue; // built at runtime: no wording to judge
                }
                // DEBUG lines are details, except a database write's row count (often DEBUG because it runs per item)
                boolean rowCount = raw.contains("[rows={}") || raw.contains(", rows={}");
                if (lc.level().compareTo(dev.logast.model.Level.INFO) < 0 && !rowCount) {
                    continue;
                }
                String text = MessageTemplate.compliantBase(raw).strip();
                String event = eventOf(phrasing, text);
                if (event == null || NOT_OUTCOMES.contains(event) || text.isBlank() || countedOrSkipped(a, m, lc, catalogs)) {
                    continue;
                }
                // a plain "completed"/"failed" of an entrypoint the framework or a wrapper times, or of an @Observed method, is in its timer already
                if ((event.equals("completed") || event.equals("failed")) && observedMethods.contains(m.key())) {
                    continue;
                }
                List<Entrypoint> eps = a.paths.entrypointsReaching(m);
                List<String> paths = eps.stream().map(e -> e.kind().name().toLowerCase(Locale.ROOT) + " " + e.label()).distinct().limit(5).toList();
                // a Reactor operator's callback runs on whichever thread emits: the running timer is not the current one there (#14)
                boolean inObservation = !inReactiveOperator(m, lc.call())
                        && eps.stream().anyMatch(e -> e.observedByFramework() || observedMethods.contains(e.method().key()));
                String subject = subject(phrasing, text, event);
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

    /** What a log line says happened (completed, failed, shipped, …): the phrasing's event, else a past-tense last word. */
    private static String eventOf(Phrasing phrasing, String text) {
        String event = phrasing.eventOf(text);
        if (event == null) {
            Matcher o = OUTCOME.matcher(text);
            event = o.find() ? o.group(1).toLowerCase(Locale.ROOT) : null;
        }
        return event;
    }

    /** The subject of an outcome line: "Order items" for "Order items closed". */
    private static String subject(Phrasing phrasing, String text, String event) {
        String fromPhrasing = phrasing.subjectOf(text);
        return clean(fromPhrasing != null ? fromPhrasing : subjectOf(text, event));
    }

    /**
     * #113: an outcome the logs report that a meter counts, for comparing services (logast fleet).
     *
     * @param meter the meter as it is counted: {@code orders.shipped}, {@code orders{outcome=shipped}}
     * @param how   what counts it: a counter in the method, a field counter, Outcomes.count, a catalog method, @Counted
     */
    public record Counted(String subject, String event, String message, String meter, String how, String method,
                          String file, int line) {
    }

    /** A counting statement in a method: its line (0: the whole method, {@code @Counted}), the meter, what counts. */
    private record Point(int line, String meter, String how) {
    }

    private static final Set<String> COUNTING = Set.of("counter", "function-counter");

    /**
     * Log lines that report an outcome (as {@link #of} recognises them) and the meter that counts them: the next counting
     * statement after the line in the same method (a counter, a field counter's {@code increment()}, Outcomes.count /
     * record, a metrics catalog method), else {@code @Counted} on the method, else the method's only counting statement
     * when the line is the method's only outcome.
     */
    public static List<Counted> counted(Analysis a) {
        Phrasing phrasing = new Phrasing(a.policy);
        List<MeterCatalog.Meter> meters = MeterCatalog.of(a).stream()
                .filter(mt -> !mt.source().equals("framework") && COUNTING.contains(mt.type())).toList();
        java.util.Map<String, String> catalogs = new HashMap<>();   // simple name -> fqn
        for (var c : a.index.classes()) {
            if (c.file().text().contains(CatalogRules.MARKER)) {
                catalogs.put(c.simpleName(), c.fqn());
            }
        }
        List<Counted> out = new ArrayList<>();
        for (MethodInfo m : a.index.methods()) {
            if (m.owner().fqn().startsWith(a.supportPackage + ".") || m.facts().logs.isEmpty() || m.decl().getBody() == null) {
                continue;
            }
            List<Point> points = countingPoints(a, m, meters, catalogs);
            if (points.isEmpty()) {
                continue;
            }
            List<LogCall> lines = new ArrayList<>();
            List<String[]> said = new ArrayList<>();   // {text, event}
            for (LogCall lc : m.facts().logs) {
                String raw = MessageTemplate.text(lc.template());
                if (raw == null || lc.level().compareTo(dev.logast.model.Level.INFO) < 0) {
                    continue;
                }
                String text = MessageTemplate.compliantBase(raw).strip();
                String event = eventOf(phrasing, text);
                if (event != null && !NOT_OUTCOMES.contains(event) && !text.isBlank()) {
                    lines.add(lc);
                    said.add(new String[]{text, event});
                }
            }
            for (int i = 0; i < lines.size(); i++) {
                int line = Pos.line(lines.get(i).call());
                Point point = points.stream().filter(p -> p.line() > line).min(Comparator.comparingInt(Point::line))
                        .or(() -> points.stream().filter(p -> p.line() == 0).findFirst())
                        .or(() -> lines.size() == 1 && points.size() == 1 ? Optional.of(points.getFirst()) : Optional.empty())
                        .orElse(null);
                if (point != null) {
                    String text = said.get(i)[0];
                    String event = said.get(i)[1];
                    out.add(new Counted(subject(phrasing, text, event), event, text, point.meter(), point.how(), m.label(),
                            m.file().relPath(), line));
                }
            }
        }
        out.sort(Comparator.comparing(Counted::file).thenComparingInt(Counted::line));
        return out;
    }

    private static List<Point> countingPoints(Analysis a, MethodInfo m, List<MeterCatalog.Meter> meters,
                                              java.util.Map<String, String> catalogs) {
        List<Point> points = new ArrayList<>();
        String rel = m.file().relPath();
        int from = Pos.line(m.decl());
        int to = Pos.endLine(m.decl().getBody());
        for (MeterCatalog.Meter mt : meters) {
            if (mt.file().equals(rel) && mt.line() >= from && mt.line() <= to) {
                boolean annotation = mt.source().equals("annotation");
                points.add(new Point(annotation ? 0 : mt.line(), mt.name(), annotation ? "@Counted" : "counter"));
            }
        }
        new org.openrewrite.java.JavaIsoVisitor<Integer>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration c, Integer p) {
                return c;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                int line = Pos.line(mi.getName());
                CatalogRules.Key k = CatalogRules.countKey(mi);
                if (k != null && !k.kind().equals("tag")) {
                    points.add(new Point(line, k.name() + "{outcome=" + k.outcome() + "}", "Outcomes." + mi.getSimpleName()));
                } else if (mi.getSelect() instanceof J.Identifier id && catalogs.containsKey(id.getSimpleName())) {
                    for (MethodInfo member : a.index.methodsOf(catalogs.get(id.getSimpleName()))) {
                        if (member.name().equals(mi.getSimpleName()) && member.decl().getBody() != null
                                && member.decl().getBody().getStatements().size() == 1
                                && member.decl().getBody().getStatements().getFirst() instanceof J.MethodInvocation inner) {
                            CatalogRules.Key ck = CatalogRules.countKey(inner);
                            if (ck != null && !ck.kind().equals("tag")) {
                                points.add(new Point(line, ck.name() + "{outcome=" + ck.outcome() + "}",
                                        id.getSimpleName() + "." + mi.getSimpleName() + "()"));
                            }
                        }
                    }
                } else if (mi.getSimpleName().equals("increment")) {
                    // a counter kept in a field: the meter registered where the field is assigned
                    String field = mi.getSelect() instanceof J.Identifier id ? id.getSimpleName()
                            : mi.getSelect() instanceof J.FieldAccess fa && fa.getTarget() instanceof J.Identifier t
                            && t.getSimpleName().equals("this") ? fa.getSimpleName() : null;
                    if (field != null) {
                        Pattern assigned = Pattern.compile("\\b" + Pattern.quote(field) + "\\s*=[^=]");
                        for (MeterCatalog.Meter mt : meters) {
                            String t = mt.file().equals(rel) && (mt.line() < from || mt.line() > to) ? m.file().lineText(mt.line()) : null;
                            if (t != null && assigned.matcher(t).find()) {
                                points.add(new Point(line, mt.name(), "field " + field));
                                break;
                            }
                        }
                    }
                }
                return super.visitMethodInvocation(mi, p);
            }
        }.visit(m.decl().getBody(), 0);
        return points;
    }

    /** An Outcomes.count/tag right after the log line, or an ignore comment for this rule on it, above it or for the file. */
    private static boolean countedOrSkipped(Analysis a, MethodInfo m, LogCall lc, Set<String> catalogs) {
        int line = Pos.line(lc.call());
        if (Suppressions.fileIgnores(m.file().text()).contains(Rules.METRIC_OUTCOME_UNMEASURED)) {
            a.ledger.ignored(m.file().relPath(), line, Rules.METRIC_OUTCOME_UNMEASURED);
            return true;
        }
        // the counting line goes after the whole statement (a chained call can run on for lines): look up to its end
        org.openrewrite.java.tree.J stmt = CoverageRules.enclosingStatement(m, lc.call());
        int end = stmt == null ? Pos.end(lc.call()) : Math.max(Pos.end(lc.call()), Pos.end(stmt));
        int last = m.file().text().substring(0, Math.min(m.file().text().length(), end)).split("\n", -1).length;
        // the counting line sits right after the statement; further down it belongs to the next statement
        for (int l = line - 1; l <= last + 1; l++) {
            String t = l < 1 ? "" : m.file().lineText(l);
            if (t == null) {
                continue;
            }
            String code = t.strip();
            if (l > line - 1 && (t.contains("Outcomes.count(") || t.contains("Outcomes.record(") || t.contains("Outcomes.tag(")
                    || catalogs.stream().anyMatch(c -> code.startsWith(c + ".") && code.contains("(")))) {
                return true;
            }
            if (l <= last && Suppressions.lineIgnores(t, Rules.METRIC_OUTCOME_UNMEASURED)) {
                a.ledger.ignored(m.file().relPath(), line, Rules.METRIC_OUTCOME_UNMEASURED);   // "skip" in metrics.yaml
                return true;
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
            Double rate = a.traffic.known() ? a.traffic.perDay(ep) : null;
            if (rate == null) {
                return "unknown: no rate for \"" + ep.label() + "\" in .logast/decisions/traffic.yaml";
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
                || ep.kind() == Entrypoint.Kind.STEP || ep.kind() == Entrypoint.Kind.QUARTZ
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
                .append(Ansi.dim(m.meters().isEmpty() ? "" : "   (logast explain metrics . --format table lists them)")).append('\n');

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
