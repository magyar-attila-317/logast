package dev.logast.metrics;

import dev.logast.Analysis;
import dev.logast.model.Annotations;
import dev.logast.model.ClassInfo;
import dev.logast.model.Entrypoint;
import dev.logast.model.MethodInfo;
import dev.logast.parse.Pos;
import org.openrewrite.Cursor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.*;

/**
 * Every meter a service publishes: created in code (MeterRegistry calls, {@code X.builder(..)}), declared by
 * annotation ({@code @Timed}, {@code @Counted}, {@code @Observed}), derived from observations
 * ({@code ObservedContext}, {@code Observation}) and the ones Spring adds for observed entrypoints.
 */
public final class MeterCatalog {
    /**
     * @param type   counter, timer, gauge, summary, long-task-timer, function-counter, observation (timer + span), ...
     * @param source       code, annotation, observation, framework
     * @param tagsComplete every tag key is known (no Tags variables or computed keys)
     */
    public record Meter(String name, String type, List<String> tags, String file, int line, String source, boolean tagsComplete) {
        Meter(String name, String type, List<String> tags, String file, int line, String source) {
            this(name, type, tags, file, line, source, true);
        }
    }

    private static final String MI = "io.micrometer.core.instrument.";
    private static final Map<String, String> REGISTRY = Map.of("counter", "counter", "timer", "timer", "summary", "summary",
            "gauge", "gauge", "gaugeCollectionSize", "gauge", "gaugeMapSize", "gauge");
    private static final Map<String, String> BUILDERS = Map.of("Counter", "counter", "Timer", "timer", "Gauge", "gauge",
            "DistributionSummary", "summary", "LongTaskTimer", "long-task-timer", "FunctionCounter", "function-counter",
            "FunctionTimer", "function-timer", "TimeGauge", "gauge");

    private MeterCatalog() {
    }

    public static List<Meter> of(Analysis a) {
        List<Meter> out = new ArrayList<>();
        framework(a, out);
        for (MethodInfo m : a.index.methods()) {
            if (m.owner().fqn().startsWith(a.supportPackage + ".")) {
                continue;
            }
            annotations(m, out);
            if (m.decl().getBody() != null) {
                code(a, m, out);
            }
        }
        out.sort(Comparator.comparing(Meter::name).thenComparing(Meter::file).thenComparingInt(Meter::line));
        return out;
    }

    private static void framework(Analysis a, List<Meter> out) {
        Map<Entrypoint.Kind, Meter> seen = new EnumMap<>(Entrypoint.Kind.class);
        for (Entrypoint ep : a.entrypoints) {
            if (!ep.observedByFramework() || seen.containsKey(ep.kind())) {
                continue;
            }
            Meter meter = switch (ep.kind()) {
                case HTTP -> new Meter("http.server.requests", "timer", List.of("method", "uri", "status", "outcome", "exception"),
                        "(Spring MVC)", 0, "framework");
                case SCHEDULED -> new Meter("tasks.scheduled.execution", "timer", List.of("code.function", "code.namespace", "outcome", "exception"),
                        "(Spring scheduling)", 0, "framework");
                case KAFKA -> new Meter("spring.kafka.listener", "timer", List.of("spring.kafka.listener.id", "messaging.destination.name", "error"),
                        "(Spring Kafka)", 0, "framework");
                default -> null;
            };
            if (meter != null) {
                seen.put(ep.kind(), meter);
                out.add(meter);
            }
        }
    }

    private static void annotations(MethodInfo m, List<Meter> out) {
        for (J.Annotation an : m.decl().getLeadingAnnotations()) {
            String type = Annotations.is(an, "io.micrometer.core.annotation.Timed") ? "timer"
                    : Annotations.is(an, "io.micrometer.core.annotation.Counted") ? "counter"
                    : Annotations.is(an, "io.micrometer.observation.annotation.Observed") ? "observation" : null;
            if (type == null) {
                continue;
            }
            String name = Annotations.stringAttribute(an, type.equals("observation") ? "name" : "value")
                    .filter(s -> !s.isBlank())
                    .orElse(type.equals("timer") ? "method.timed" : type.equals("counter") ? "method.counted" : "method.observed");
            out.add(new Meter(name, type, List.of("class", "method"), m.file().relPath(), Pos.line(an), "annotation"));
        }
    }

    private static void code(Analysis a, MethodInfo m, List<Meter> out) {
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration c, Integer p) {
                return c;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                JavaType.Method mt = mi.getMethodType();
                if (mt != null) {
                    String owner = mt.getDeclaringType().getFullyQualifiedName();
                    String name = mt.getName();
                    List<Expression> args = mi.getArguments().stream().filter(x -> !(x instanceof J.Empty)).toList();
                    String meterName = args.isEmpty() ? null : constant(a, args.getFirst());
                    int line = Pos.line(mi.getName());
                    if (owner.startsWith(MI + "MeterRegistry") && REGISTRY.containsKey(name) && meterName != null) {
                        List<String> tags = new ArrayList<>(literalKeys(args.subList(1, args.size())));
                        args.stream().skip(1).forEach(x -> tags.addAll(tagsOfExpression(x)));
                        boolean complete = args.stream().skip(1).noneMatch(MeterCatalog::opaqueTags)
                                && args.stream().skip(1).filter(x -> x instanceof J.Literal).count() % 2 == 0;
                        out.add(new Meter(meterName, REGISTRY.get(name), dedupe(tags), m.file().relPath(), line, "code", complete));
                    } else if (name.equals("builder") && owner.startsWith(MI) && meterName != null
                            && BUILDERS.containsKey(owner.substring(MI.length()))) {
                        boolean[] complete = {true};
                        List<String> tags = chainTags(getCursor(), complete);
                        out.add(new Meter(meterName, BUILDERS.get(owner.substring(MI.length())), dedupe(tags),
                                m.file().relPath(), line, "code", complete[0]));
                    } else if (meterName != null && (owner.endsWith(".ObservedContext") && Set.of("entrypoint", "observed").contains(name)
                            || owner.equals("io.micrometer.observation.Observation") && Set.of("createNotStarted", "start").contains(name))) {
                        out.add(new Meter(meterName, "observation", List.of("error"), m.file().relPath(), line, "observation"));
                    }
                }
                return super.visitMethodInvocation(mi, p);
            }
        }.visit(m.decl().getBody(), 0, new Cursor(new Cursor(null, Cursor.ROOT_VALUE), m.decl()));
    }

    /** Tag keys added along a builder chain: {@code Counter.builder("x").tag("a", ..).tags("b", .., "c", ..)}. */
    private static List<String> chainTags(Cursor c, boolean[] complete) {
        List<String> keys = new ArrayList<>();
        Object current = c.getValue();
        for (Cursor p = c.getParentTreeCursor(); p.getValue() instanceof J.MethodInvocation outer && outer.getSelect() == current;
             p = p.getParentTreeCursor()) {
            List<Expression> args = outer.getArguments().stream().filter(x -> !(x instanceof J.Empty)).toList();
            if (outer.getSimpleName().equals("tag") || outer.getSimpleName().equals("tags")) {
                keys.addAll(literalKeys(args));
                args.forEach(x -> keys.addAll(tagsOfExpression(x)));
                if (args.stream().anyMatch(MeterCatalog::opaqueTags) || !args.isEmpty() && !(args.getFirst() instanceof J.Literal)
                        && tagsOfExpression(args.getFirst()).isEmpty()) {
                    complete[0] = false;
                }
            }
            current = outer;
        }
        return keys;
    }

    /** Keys at even positions of a literal key/value list. */
    private static List<String> literalKeys(List<Expression> kv) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i + 1 < kv.size(); i += 2) {
            if (kv.get(i) instanceof J.Literal l && l.getValue() instanceof String k) {
                keys.add(k);
            }
        }
        return keys;
    }

    /** {@code Tags.of("a", x, "b", y)} / {@code Tag.of("a", x)} inside an argument. */
    private static List<String> tagsOfExpression(Expression e) {
        if (e instanceof J.MethodInvocation mi && mi.getSimpleName().equals("of") && mi.getSelect() instanceof J.Identifier id
                && (id.getSimpleName().equals("Tags") || id.getSimpleName().equals("Tag"))) {
            return literalKeys(mi.getArguments().stream().filter(x -> !(x instanceof J.Empty)).toList());
        }
        return List.of();
    }

    /** A tag collection whose keys are not visible here (a Tags/Iterable variable or call). */
    private static boolean opaqueTags(Expression e) {
        return TypeUtils.isAssignableTo("java.lang.Iterable", e.getType()) && tagsOfExpression(e).isEmpty();
    }

    private static List<String> dedupe(List<String> keys) {
        return List.copyOf(new LinkedHashSet<>(keys));
    }

    /** A string literal, or a {@code static final String} constant of a project class ({@code METRIC}, {@code Metrics.ORDERS}). */
    static String constant(Analysis a, Expression e) {
        if (e instanceof J.Literal l) {
            return l.getValue() instanceof String s ? s : null;
        }
        JavaType.Variable field = e instanceof J.Identifier id ? id.getFieldType()
                : e instanceof J.FieldAccess fa ? fa.getName().getFieldType() : null;
        if (field == null || !(field.getOwner() instanceof JavaType.FullyQualified owner) || !TypeUtils.isString(field.getType())) {
            return null;
        }
        ClassInfo c = a.index.clazz(owner.getFullyQualifiedName());
        if (c == null) {
            return null;
        }
        for (Statement s : c.decl().getBody().getStatements()) {
            if (s instanceof J.VariableDeclarations vd) {
                for (J.VariableDeclarations.NamedVariable v : vd.getVariables()) {
                    if (v.getSimpleName().equals(field.getName()) && v.getInitializer() instanceof J.Literal l
                            && l.getValue() instanceof String str) {
                        return str;
                    }
                }
            }
        }
        return null;
    }

    /** Plain-text table for the terminal. */
    public static String table(List<Meter> meters) {
        if (meters.isEmpty()) {
            return "no meters found\n";
        }
        int w = Math.max(20, meters.stream().mapToInt(x -> x.name().length()).max().orElse(20)) + 2;
        StringBuilder sb = new StringBuilder();
        for (Meter x : meters) {
            sb.append(String.format("%-" + w + "s %-16s %-30s %s%n", x.name(), x.type(),
                    x.tags().isEmpty() ? "-" : String.join(",", x.tags()),
                    x.source().equals("framework") ? x.file() : x.file() + ":" + x.line()));
        }
        long names = meters.stream().map(Meter::name).distinct().count();
        sb.append(names).append(" meters (").append(meters.size()).append(" definitions)\n");
        return sb.toString();
    }
}
