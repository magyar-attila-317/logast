package dev.logast.queries;

import dev.logast.Analysis;
import dev.logast.graph.Constants;
import dev.logast.metrics.MeterCatalog;
import dev.logast.model.Entrypoint;
import dev.logast.model.MethodInfo;
import dev.logast.parse.Pos;
import org.openrewrite.Cursor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

import java.util.*;

/**
 * What a service emits that a query can find (#106): the timer behind each entrypoint, the counters and summaries in
 * code (Micrometer builders and annotations, and the {@code Outcomes} helpers of logging-support), the business ids in
 * MDC and the log format. Read from the code as it stands, so it is best run after {@code logast apply}.
 */
public record ServiceSignals(String service, String logFormat, String promServiceLabel, List<Flow> flows,
                             List<Meter> meters, List<String> mdcKeys) {

    /** How an entrypoint is timed. */
    public enum Timing { HTTP, SCHEDULED, KAFKA, OBSERVATION }

    /**
     * An entrypoint and the timer that measures it.
     *
     * @param metric the meter name as Micrometer names it (dots), e.g. {@code http.server.requests}
     * @param tags   the tag values that pick this entrypoint out of the meter (empty: the meter is its own)
     */
    public record Flow(String kind, String label, Timing timing, String metric, SequencedMap<String, String> tags,
                       String file, int line) {
    }

    /**
     * A meter written in the service's code.
     *
     * @param type     counter, summary, timer, observation (a timer, from an Observation)
     * @param outcomes the {@code outcome} tag values the code passes, where known (Outcomes.count)
     */
    public record Meter(String name, String type, List<String> tags, SortedSet<String> outcomes, String file, int line) {
    }

    /** The signals of the analysed service; {@code mdcKeys}: vocabulary keys some method has in MDC (the business ids). */
    public static ServiceSignals of(Analysis a) {
        String service = a.springProperties.getOrDefault("spring.application.name", "");
        if (service.isBlank() || service.contains("${")) {
            service = a.project.root().getFileName().toString();
        }
        String format = logFormat(a);
        String label = a.policy.queries().prometheus().serviceLabel();
        if (label.isEmpty()) {
            label = a.springProperties.containsKey("management.metrics.tags.application") ? "application" : "job";
        }

        List<Flow> flows = new ArrayList<>();
        for (Entrypoint ep : a.entrypoints) {
            Flow f = flow(a, ep);
            if (f != null) {
                flows.add(f);
            }
        }

        Map<String, Meter> meters = new TreeMap<>();
        for (MeterCatalog.Meter m : MeterCatalog.of(a)) {
            if (m.source().equals("framework")) {
                continue;
            }
            String type = m.type().equals("observation") ? "observation" : m.type();
            meters.putIfAbsent(m.name() + " " + type, new Meter(m.name(), type, m.tags(), new TreeSet<>(), m.file(), m.line()));
        }
        outcomes(a, meters);

        Set<String> registry = a.policy.mdcRegistry().keySet();
        Set<String> keys = new TreeSet<>();
        for (MethodInfo m : a.index.methods()) {
            if (!m.owner().fqn().startsWith(a.supportPackage + ".")) {
                a.paths.mdcInside(m).stream().filter(registry::contains).forEach(keys::add);
            }
        }
        return new ServiceSignals(service, format, label, flows, List.copyOf(meters.values()), List.copyOf(keys));
    }

    /**
     * The JSON format the service's logs are in, decided as SETUP_SERVICE_IDENTITY decides it: a non-default
     * logging.structuredFormat of logast.yaml (apply switches the service to it), else Spring Boot's
     * logging.structured.format.console, else what the service already writes (a Logstash or GELF encoder in its
     * Logback config or build), else ECS. Each format names its fields differently ({@code log.level} vs {@code level}).
     */
    static String logFormat(Analysis a) {
        String policy = a.policy.logging().structuredFormat();
        String format = !policy.equals("ecs") && !policy.equals("none") ? policy
                : a.springProperties.getOrDefault("logging.structured.format.console", policy);
        if (format.equals("ecs") || format.equals("none")) {
            String used = dev.logast.ServiceSetup.detectLogFormat(a.project.root());
            format = used != null ? used : "ecs";
        }
        return format;
    }

    private static Flow flow(Analysis a, Entrypoint ep) {
        MethodInfo m = ep.method();
        String kind = ep.kind().name().toLowerCase(Locale.ROOT);
        String observation = observationName(a, m);
        if (observation != null) {
            return new Flow(kind, ep.label(), Timing.OBSERVATION, observation, new LinkedHashMap<>(), m.file().relPath(), m.line());
        }
        if (!ep.observedByFramework()) {
            return null;
        }
        SequencedMap<String, String> tags = new LinkedHashMap<>();
        return switch (ep.kind()) {
            case HTTP -> {
                String[] mu = ep.label().split(" ", 2);
                tags.put("method", mu[0]);
                tags.put("uri", mu.length > 1 ? mu[1] : "");
                yield new Flow(kind, ep.label(), Timing.HTTP, "http.server.requests", tags, m.file().relPath(), m.line());
            }
            case SCHEDULED -> {
                tags.put("code.namespace", m.owner().fqn());
                tags.put("code.function", m.name());
                yield new Flow(kind, ep.label(), Timing.SCHEDULED, "tasks.scheduled.execution", tags, m.file().relPath(), m.line());
            }
            case KAFKA -> new Flow(kind, ep.label(), Timing.KAFKA, "spring.kafka.listener", tags, m.file().relPath(), m.line());
            default -> null;
        };
    }

    /** The name passed to {@code ObservedContext.entrypoint(..)} / {@code observed(..)} in the method's own body. */
    private static String observationName(Analysis a, MethodInfo m) {
        if (m.decl().getBody() == null) {
            return null;
        }
        String[] found = {null};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration c, Integer p) {
                return c;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                JavaType.Method mt = mi.getMethodType();
                if (found[0] == null && mt != null && mt.getDeclaringType().getFullyQualifiedName().endsWith(".ObservedContext")
                        && (mt.getName().equals("entrypoint") || mt.getName().equals("observed"))) {
                    List<Expression> args = args(mi);
                    found[0] = args.isEmpty() ? null : Constants.string(a.index, args.getFirst());
                }
                return super.visitMethodInvocation(mi, p);
            }
        }.visit(m.decl().getBody(), 0, new Cursor(new Cursor(null, Cursor.ROOT_VALUE), m.decl()));
        return found[0];
    }

    /**
     * {@code Outcomes.count(name, outcome[, amountName, amount])} counts {@code name{outcome}} and adds the amount to the
     * counter {@code name.amountName}; {@code Outcomes.record} counts the same and records the amount in the summary
     * {@code name.amountName} (logging-support's Outcomes). Name and outcome must be constants to be found.
     */
    private static void outcomes(Analysis a, Map<String, Meter> meters) {
        for (MethodInfo m : a.index.methods()) {
            if (m.decl().getBody() == null || m.owner().fqn().startsWith(a.supportPackage + ".")) {
                continue;
            }
            new JavaIsoVisitor<Integer>() {
                @Override
                public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration c, Integer p) {
                    return c;
                }

                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                    JavaType.Method mt = mi.getMethodType();
                    if (mt != null && mt.getDeclaringType().getFullyQualifiedName().endsWith(".Outcomes")
                            && (mt.getName().equals("count") || mt.getName().equals("record"))) {
                        List<Expression> args = args(mi);
                        String name = args.isEmpty() ? null : Constants.string(a.index, args.getFirst());
                        String outcome = args.size() < 2 ? null : Constants.string(a.index, args.get(1));
                        if (name != null) {
                            int line = Pos.line(mi.getName());
                            add(meters, name, "counter", outcome, m, line);
                            String amount = args.size() < 3 ? null : Constants.string(a.index, args.get(2));
                            if (amount != null) {
                                add(meters, name + "." + amount, mt.getName().equals("record") ? "summary" : "counter", outcome, m, line);
                            }
                        }
                    }
                    return super.visitMethodInvocation(mi, p);
                }
            }.visit(m.decl().getBody(), 0, new Cursor(new Cursor(null, Cursor.ROOT_VALUE), m.decl()));
        }
    }

    private static void add(Map<String, Meter> meters, String name, String type, String outcome, MethodInfo m, int line) {
        Meter mt = meters.computeIfAbsent(name + " " + type,
                k -> new Meter(name, type, List.of("outcome"), new TreeSet<>(), m.file().relPath(), line));
        if (outcome != null) {
            mt.outcomes().add(outcome);
        }
    }

    private static List<Expression> args(J.MethodInvocation mi) {
        return mi.getArguments().stream().filter(x -> !(x instanceof J.Empty)).toList();
    }
}
