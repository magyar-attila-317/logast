package dev.logast.rules;

import dev.logast.model.Entrypoint;
import dev.logast.model.MethodInfo;
import dev.logast.orders.Edit;
import dev.logast.parse.Pos;
import dev.logast.parse.SourceFileInfo;
import dev.logast.policy.Policy;
import org.openrewrite.Cursor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.*;
import java.util.regex.Pattern;

/**
 * {@code metrics} pack:
 * <ul>
 *   <li>METRIC_NAMING: meter names are lowercase dot-separated and use vocabulary terms (mechanical rename);</li>
 *   <li>METRIC_HIGH_CARDINALITY_TAG: an id (vocabulary key with {@code cardinality: high}) or PII used as a tag;</li>
 *   <li>METRIC_TAG_KEY / METRIC_TAG_VALUE: tag keys from the tag vocabulary ({@code result} → {@code outcome}),
 *       literal values from its allowed list;</li>
 *   <li>METRIC_DIRECT_REGISTRY: a hand-made timer in an entrypoint the framework already times (Observation);</li>
 *   <li>METRIC_REGISTERED_PER_CALL: a meter looked up / built on every call instead of once;</li>
 *   <li>METRIC_GAUGE_WEAK_REF: a gauge on an object nothing else holds: Micrometer keeps a weak reference, the gauge
 *       reads NaN once it is collected.</li>
 * </ul>
 */
final class MetricRules {
    /** Lowercase, dot-separated; underscores inside a segment are a valid multi-word style (goods_receipt). */
    private static final Pattern GOOD_NAME = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*");
    private static final Set<String> REGISTRY_METERS = Set.of("counter", "timer", "summary", "gauge", "more");
    private static final Set<String> REGISTRY_CREATES = Set.of("counter", "timer", "summary");
    private static final Set<String> REGISTRY_GAUGES = Set.of("gauge", "gaugeCollectionSize", "gaugeMapSize");
    private static final Set<String> SETUP_METHODS = Set.of("bindTo", "afterPropertiesSet", "init", "initialize",
            "onApplicationEvent", "customize", "configure");
    private static final Set<String> SETUP_ANNOTATIONS = Set.of("org.springframework.context.annotation.Bean",
            "jakarta.annotation.PostConstruct", "javax.annotation.PostConstruct", "org.springframework.context.event.EventListener");

    private final RuleContext ctx;

    MetricRules(RuleContext ctx) {
        this.ctx = ctx;
    }

    void run() {
        Set<String> observedEntrypoints = new HashSet<>();
        for (Entrypoint ep : ctx.a.entrypoints) {
            if (ep.observedByFramework()) {
                observedEntrypoints.add(ep.method().key());
            }
        }
        for (MethodInfo m : ctx.methods()) {
            if (m.decl().getBody() == null) {
                continue;
            }
            SourceFileInfo f = m.file();
            int[] n = {0};
            boolean timedEntrypoint = observedEntrypoints.contains(m.key());
            boolean setup = setupMethod(m);
            Set<String> params = new HashSet<>();
            for (Statement p : m.decl().getParameters()) {
                if (p instanceof J.VariableDeclarations vd) {
                    vd.getVariables().forEach(v -> params.add(v.getSimpleName()));
                }
            }
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
                        boolean registry = owner.startsWith("io.micrometer.core.instrument.MeterRegistry")
                                && REGISTRY_METERS.contains(name);
                        boolean builder = owner.startsWith("io.micrometer.core.instrument.") && name.equals("builder");
                        List<Expression> args = mi.getArguments().stream().filter(a -> !(a instanceof J.Empty)).toList();
                        if ((registry || builder) && !args.isEmpty() && args.getFirst() instanceof J.Literal lit
                                && lit.getValue() instanceof String meter) {
                            naming(m, lit, meter, n[0]++);
                        }
                        if (registry && args.size() > 2 && !name.equals("gauge")) {
                            tags(m, mi, args.subList(1, args.size()), n[0]++);
                        }
                        if ((name.equals("of") && (owner.equals("io.micrometer.core.instrument.Tags")
                                || owner.equals("io.micrometer.core.instrument.Tag")))
                                || (name.equals("tag") && owner.startsWith("io.micrometer.core.instrument."))
                                || (name.equals("tags") && owner.startsWith("io.micrometer.core.instrument.") && args.size() > 1)) {
                            tags(m, mi, args, n[0]++);
                        }
                        if (timedEntrypoint && ctx.enabled(Rules.METRIC_DIRECT_REGISTRY)
                                && (owner.equals("io.micrometer.core.instrument.Timer") && name.equals("start")
                                || registry && name.equals("timer"))) {
                            int line = Pos.line(mi.getName());
                            if (!ctx.ignored(f, line, Rules.METRIC_DIRECT_REGISTRY)) {
                                ctx.order(Rules.METRIC_DIRECT_REGISTRY, m, line, "timer" + n[0]++)
                                        .title("Hand-made timer in an entrypoint the framework already times (Observation → timer + span)")
                                        .suggestion("Drop it and use the observation's timer (e.g. http.server.requests), or add a "
                                                + "low-cardinality tag to that observation instead of a second meter.")
                                        .context(ctx.context(m, Pos.start(mi), Text.oneLine(mi.printTrimmed(), 160), null));
                            }
                        }
                        boolean creates = owner.startsWith("io.micrometer.core.instrument.MeterRegistry") && REGISTRY_CREATES.contains(name)
                                || name.equals("register") && owner.startsWith("io.micrometer.core.instrument.") && owner.contains("$Builder");
                        // Micrometer caches meters by id: a lookup per call only matters where it repeats per item
                        if (creates && !setup && inLoop(getCursor()) && !keptOrCached(getCursor())) {
                            perCall(m, mi, n[0]++);
                        }
                        if (owner.startsWith("io.micrometer.core.instrument.MeterRegistry") && REGISTRY_GAUGES.contains(name)) {
                            Expression obj = args.size() > 2 && TypeUtils.isAssignableTo("java.lang.Iterable", args.get(1).getType())
                                    ? args.get(2) : args.size() > 1 ? args.get(1) : null;
                            if (weak(obj, params) && !assignedToField(getCursor())) {
                                weakGauge(m, mi, obj, n[0]++);
                            }
                        }
                        if (builder && owner.equals("io.micrometer.core.instrument.Gauge") && args.size() > 1
                                && weak(args.get(1), params) && !chainHas(getCursor(), "strongReference")) {
                            weakGauge(m, mi, args.get(1), n[0]++);
                        }
                    }
                    return super.visitMethodInvocation(mi, p);
                }
            }.visit(m.decl().getBody(), 0, new Cursor(new Cursor(null, Cursor.ROOT_VALUE), m.decl()));
        }
    }

    /**
     * METRIC_TAG_KEYS_MISMATCH: one meter name registered with different tag keys. Prometheus needs one set of keys per
     * name; Micrometer's Prometheus registry refuses the later registration, so those values never reach Prometheus.
     */
    static void tagKeyMismatch(RuleContext ctx) {
        if (!ctx.enabled(Rules.METRIC_TAG_KEYS_MISMATCH)) {
            return;
        }
        Map<String, List<dev.logast.metrics.MeterCatalog.Meter>> byName = new TreeMap<>();
        for (var meter : dev.logast.metrics.MeterCatalog.of(ctx.a)) {
            if (meter.source().equals("code")) {
                byName.computeIfAbsent(meter.name(), k -> new ArrayList<>()).add(meter);
            }
        }
        byName.forEach((name, defs) -> {
            if (defs.size() < 2 || defs.stream().anyMatch(d -> !d.tagsComplete())) {
                return;
            }
            Set<Set<String>> keySets = new LinkedHashSet<>();
            defs.forEach(d -> keySets.add(new TreeSet<>(d.tags())));
            if (keySets.size() < 2) {
                return;
            }
            var first = defs.getFirst();
            OrderBuilder b = ctx.order(Rules.METRIC_TAG_KEYS_MISMATCH, "tagkeys|" + name).at(first.file(), first.line())
                    .title("Meter '" + name + "' is registered with different tag keys " + keySets
                            + ": Prometheus accepts one set per name, the other registrations are dropped")
                    .suggestion("Use the same tag keys everywhere (e.g. add reason=\"none\" to the success counter), or give the "
                            + "variants different names.");
            defs.stream().skip(1).forEach(d -> b.location(d.file(), d.line(), Rules.METRIC_TAG_KEYS_MISMATCH));
        });
    }

    /** Constructors and one-time registration hooks (MeterBinder.bindTo, @Bean, @PostConstruct, ...). */
    private boolean setupMethod(MethodInfo m) {
        if (m.decl().isConstructor() || SETUP_METHODS.contains(m.name())
                || TypeUtils.isAssignableTo("io.micrometer.core.instrument.binder.MeterBinder", m.owner().type())) {
            return true;
        }
        return SETUP_ANNOTATIONS.stream().anyMatch(m::hasAnnotation);
    }

    /** The meter is stored in a field, or built inside a cache (computeIfAbsent): created once per key. */
    private static boolean keptOrCached(Cursor c) {
        if (assignedToField(c)) {
            return true;
        }
        for (Cursor p = c.getParent(); p != null; p = p.getParent()) {
            if (p.getValue() instanceof J.MethodInvocation outer && Set.of("computeIfAbsent", "computeIfPresent", "compute",
                    "get", "getOrCompute").contains(outer.getSimpleName()) && p != c) {
                return true;
            }
            if (p.getValue() instanceof J.MethodDeclaration) {
                break;
            }
        }
        return false;
    }

    private static boolean assignedToField(Cursor c) {
        Cursor parent = c.getParentTreeCursor();
        if (parent.getValue() instanceof J.MethodInvocation chained && chained.getSelect() == c.getValue()) {
            return false;   // registry.counter(..).increment(): used once, not stored
        }
        if (parent.getValue() instanceof J.Assignment as) {
            Expression v = as.getVariable();
            return v instanceof J.FieldAccess || v instanceof J.Identifier id && isField(id);
        }
        return false;
    }

    private static boolean inLoop(Cursor c) {
        for (Cursor p = c.getParent(); p != null; p = p.getParent()) {
            Object v = p.getValue();
            if (v instanceof J.ForLoop || v instanceof J.ForEachLoop || v instanceof J.WhileLoop || v instanceof J.DoWhileLoop) {
                return true;
            }
            if (v instanceof J.Lambda && p.getParentTreeCursor().getValue() instanceof J.MethodInvocation call
                    && Set.of("forEach", "map", "flatMap", "filter", "peek", "forEachOrdered").contains(call.getSimpleName())) {
                return true;
            }
            if (v instanceof J.MethodDeclaration) {
                return false;
            }
        }
        return false;
    }

    private static boolean chainHas(Cursor c, String method) {
        for (Cursor p = c.getParentTreeCursor(); p.getValue() instanceof J.MethodInvocation outer; p = p.getParentTreeCursor()) {
            if (outer.getSimpleName().equals(method)) {
                return true;
            }
        }
        return false;
    }

    /** A new object, or a local variable (not a field, not a parameter): nothing else keeps it alive. */
    private static boolean weak(Expression obj, Set<String> params) {
        if (obj == null) {
            return false;
        }
        if (obj instanceof J.NewClass) {
            return true;
        }
        return obj instanceof J.Identifier id && !isField(id) && !params.contains(id.getSimpleName())
                && !(id.getType() instanceof JavaType.Primitive);
    }

    /** A field, not a local or parameter (OpenRewrite types locals as variables owned by their method). */
    private static boolean isField(J.Identifier id) {
        return id.getFieldType() != null && id.getFieldType().getOwner() instanceof JavaType.FullyQualified;
    }

    private void perCall(MethodInfo m, J.MethodInvocation mi, int ordinal) {
        int line = Pos.line(mi.getName());
        if (!ctx.enabled(Rules.METRIC_REGISTERED_PER_CALL) || ctx.ignored(m.file(), line, Rules.METRIC_REGISTERED_PER_CALL)) {
            return;
        }
        ctx.order(Rules.METRIC_REGISTERED_PER_CALL, m, line, "percall" + ordinal)
                .title("Meter looked up / built for every item of a loop in " + m.label() + " (registry lookup and allocation per item)")
                .suggestion("Create it once (constructor or MeterBinder) and keep it in a field; for per-value tags cache the "
                        + "meters (Map.computeIfAbsent) or use Meter.MeterProvider (Counter.builder(..).withRegistry(registry)).")
                .context(ctx.context(m, Pos.start(mi), Text.oneLine(mi.printTrimmed(), 160), null));
    }

    private void weakGauge(MethodInfo m, J.MethodInvocation mi, Expression obj, int ordinal) {
        int line = Pos.line(mi.getName());
        if (!ctx.enabled(Rules.METRIC_GAUGE_WEAK_REF) || ctx.ignored(m.file(), line, Rules.METRIC_GAUGE_WEAK_REF)) {
            return;
        }
        ctx.order(Rules.METRIC_GAUGE_WEAK_REF, m, line, "gauge" + ordinal)
                .title("Gauge on '" + Text.oneLine(obj.printTrimmed(), 60) + "', which nothing else references: "
                        + "Micrometer holds it weakly, the gauge reads NaN after the next garbage collection")
                .suggestion("Keep the measured object in a field (this.active = registry.gauge(\"…\", new AtomicInteger())), "
                        + "or use Gauge.builder(..).strongReference(true).")
                .context(ctx.context(m, Pos.start(mi), Text.oneLine(mi.printTrimmed(), 160), null));
    }

    private void naming(MethodInfo m, J.Literal lit, String name, int ordinal) {
        if (!ctx.enabled(Rules.METRIC_NAMING)) {
            return;
        }
        String fixed = canonicalName(name, ctx.area(m));
        int line = Pos.line(lit);
        if (fixed.equals(name) || ctx.ignored(m.file(), line, Rules.METRIC_NAMING)) {
            return;
        }
        ctx.order(Rules.METRIC_NAMING, m, line, "name" + ordinal)
                .title("Meter name '" + name + "' → '" + fixed + "' (lowercase, dot-separated, vocabulary terms)")
                .suggestion("Renaming a meter starts a new time series: update dashboards and alerts that query '" + name
                        + "' in the same change (or keep the old name with // logast:ignore METRIC_NAMING).")
                .edit(Edit.replace(m.file().relPath(), Pos.start(lit), Pos.end(lit), "\"" + fixed + "\""))
                .context(ctx.context(m, Pos.start(lit), name, null));
    }

    /** ordersPlaced / orders_placed / orders-placed → orders.placed; aliases → vocabulary terms per segment. */
    String canonicalName(String name, String area) {
        return canonicalName(ctx.policy, name, area);
    }

    static String canonicalName(Policy policy, String name, String area) {
        String dotted = name.replaceAll("([a-z0-9])([A-Z])", "$1.$2").replaceAll("[\\-\\s]+", ".").toLowerCase(Locale.ROOT);
        Map<String, String> synonyms = policy.synonyms(area);
        List<String> parts = new ArrayList<>();
        for (String seg : dotted.split("\\.")) {
            if (seg.isEmpty()) {
                continue;
            }
            if (seg.contains("_")) {
                parts.add(seg); // multi-word segment: leave its words alone
                continue;
            }
            String singular = seg.endsWith("s") ? seg.substring(0, seg.length() - 1) : seg;
            if (synonyms.containsKey(seg)) {
                seg = synonyms.get(seg);
            } else if (synonyms.containsKey(singular)) {
                seg = synonyms.get(singular) + "s";
            }
            parts.add(seg);
        }
        String out = String.join(".", parts);
        return GOOD_NAME.matcher(out).matches() ? out : name;
    }

    /** Key/value pairs: tag vocabulary (key aliases, allowed values), high-cardinality ids and PII. */
    private void tags(MethodInfo m, J.MethodInvocation mi, List<Expression> kv, int ordinal) {
        Map<String, String> aliases = ctx.policy.mdcAliases();
        Map<String, String> tagAliases = ctx.policy.metrics().tagAliases();
        for (int i = 0; i + 1 < kv.size(); i += 2) {
            if (!(kv.get(i) instanceof J.Literal l) || !(l.getValue() instanceof String key)) {
                continue;
            }
            int line = Pos.line(l);
            Expression value = kv.get(i + 1);
            String tagKey = tagAliases.getOrDefault(key, key);
            if (!tagKey.equals(key) && ctx.enabled(Rules.METRIC_TAG_KEY) && !ctx.ignored(m.file(), line, Rules.METRIC_TAG_KEY)) {
                ctx.order(Rules.METRIC_TAG_KEY, m, line, "tagkey" + ordinal + "|" + key)
                        .title("Metric tag '" + key + "' → '" + tagKey + "' (tag vocabulary)")
                        .suggestion("Renaming a tag changes the series: update dashboards and alerts that filter on '" + key + "'.")
                        .edit(Edit.replace(m.file().relPath(), Pos.start(l), Pos.end(l), "\"" + tagKey + "\""))
                        .context(ctx.context(m, Pos.start(mi), Text.oneLine(mi.printTrimmed(), 160), null));
            }
            Policy.MetricTag def = ctx.policy.metrics().tags().get(tagKey);
            if (def != null && !def.values().isEmpty() && value instanceof J.Literal vl && vl.getValue() instanceof String v
                    && !def.values().contains(v) && ctx.enabled(Rules.METRIC_TAG_VALUE)
                    && !ctx.ignored(m.file(), Pos.line(vl), Rules.METRIC_TAG_VALUE)) {
                ctx.order(Rules.METRIC_TAG_VALUE, m, Pos.line(vl), "tagvalue" + ordinal + "|" + key)
                        .title("Metric tag " + tagKey + "=\"" + v + "\" is not one of " + def.values())
                        .suggestion("Use one of " + def.values() + " so dashboards can compare services; extend the list in the "
                                + "policy (metrics.tags." + tagKey + ".values) if this is a real new value.")
                        .context(ctx.context(m, Pos.start(mi), Text.oneLine(mi.printTrimmed(), 160), null));
            }
            if (DomainValues.isConstant(value) || !ctx.enabled(Rules.METRIC_HIGH_CARDINALITY_TAG)) {
                continue; // constant value: one series, whatever the key is called
            }
            String canonical = aliases.getOrDefault(key, key);
            Optional<String> valueKey = ctx.domain.keyOf(value);
            boolean high = ctx.policy.highCardinality(canonical) || valueKey.map(ctx.policy::highCardinality).orElse(false);
            boolean pii = ctx.domain.isPii(value);
            if ((high || pii) && !ctx.ignored(m.file(), line, Rules.METRIC_HIGH_CARDINALITY_TAG)) {
                ctx.order(Rules.METRIC_HIGH_CARDINALITY_TAG, m, line, "tag" + ordinal + "|" + key)
                        .title("Metric tag '" + key + "' " + (pii ? "carries PII" : "is an id (unbounded values)")
                                + ": every distinct value creates a new time series")
                        .suggestion("Remove the tag; put the id on the span/MDC instead (ObservedContext.mdc(\"" + canonical
                                + "\", …) adds it to both). Keep only low-cardinality tags (status, channel, type).")
                        .context(ctx.context(m, Pos.start(mi), Text.oneLine(mi.printTrimmed(), 160), null));
            }
        }
    }
}
