package dev.logast.rules;

import dev.logast.model.Entrypoint;
import dev.logast.model.MethodInfo;
import dev.logast.orders.Edit;
import dev.logast.parse.Pos;
import dev.logast.parse.SourceFileInfo;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

import java.util.*;
import java.util.regex.Pattern;

/**
 * {@code metrics} pack:
 * <ul>
 *   <li>METRIC_NAMING: meter names are lowercase dot-separated and use vocabulary terms (mechanical rename);</li>
 *   <li>METRIC_HIGH_CARDINALITY_TAG: an id (vocabulary key with {@code cardinality: high}) or PII used as a tag;</li>
 *   <li>METRIC_DIRECT_REGISTRY: a hand-made timer in an entrypoint the framework already times (Observation).</li>
 * </ul>
 */
final class MetricRules {
    /** Lowercase, dot-separated; underscores inside a segment are a valid multi-word style (goods_receipt). */
    private static final Pattern GOOD_NAME = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*");
    private static final Set<String> REGISTRY_METERS = Set.of("counter", "timer", "summary", "gauge", "more");
    private static final Set<String> BUILDERS = Set.of("builder");

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
                        boolean registry = owner.startsWith("io.micrometer.core.instrument.MeterRegistry")
                                && REGISTRY_METERS.contains(mt.getName());
                        boolean builder = owner.startsWith("io.micrometer.core.instrument.") && BUILDERS.contains(mt.getName());
                        List<Expression> args = mi.getArguments().stream().filter(a -> !(a instanceof J.Empty)).toList();
                        if ((registry || builder) && !args.isEmpty() && args.getFirst() instanceof J.Literal lit
                                && lit.getValue() instanceof String name) {
                            naming(m, lit, name, n[0]++);
                        }
                        if (registry && args.size() > 2) {
                            tags(m, mi, args.subList(1, args.size()), n[0]++);
                        }
                        if ((mt.getName().equals("of") && owner.equals("io.micrometer.core.instrument.Tags"))
                                || (mt.getName().equals("tag") && owner.startsWith("io.micrometer.core.instrument."))) {
                            tags(m, mi, args, n[0]++);
                        }
                        if (timedEntrypoint && ctx.enabled(Rules.METRIC_DIRECT_REGISTRY)
                                && (owner.equals("io.micrometer.core.instrument.Timer") && mt.getName().equals("start")
                                || registry && mt.getName().equals("timer"))) {
                            int line = Pos.line(mi.getName());
                            if (!ctx.ignored(f, line, Rules.METRIC_DIRECT_REGISTRY)) {
                                ctx.order(Rules.METRIC_DIRECT_REGISTRY, m, line, "timer" + n[0]++)
                                        .title("Hand-made timer in an entrypoint the framework already times (Observation → timer + span)")
                                        .suggestion("Drop it and use the observation's timer (e.g. http.server.requests), or add a "
                                                + "low-cardinality tag to that observation instead of a second meter.")
                                        .context(ctx.context(m, Pos.start(mi), Text.oneLine(mi.printTrimmed(), 160), null));
                            }
                        }
                    }
                    return super.visitMethodInvocation(mi, p);
                }
            }.visit(m.decl().getBody(), 0);
        }
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
        String dotted = name.replaceAll("([a-z0-9])([A-Z])", "$1.$2").replaceAll("[\\-\\s]+", ".").toLowerCase(Locale.ROOT);
        Map<String, String> synonyms = ctx.policy.synonyms(area);
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

    /** Key/value pairs: a high-cardinality vocabulary key (or its alias), a mapped id value, or PII. */
    private void tags(MethodInfo m, J.MethodInvocation mi, List<Expression> kv, int ordinal) {
        if (!ctx.enabled(Rules.METRIC_HIGH_CARDINALITY_TAG)) {
            return;
        }
        Map<String, String> aliases = ctx.policy.mdcAliases();
        for (int i = 0; i + 1 < kv.size(); i += 2) {
            if (!(kv.get(i) instanceof J.Literal l) || !(l.getValue() instanceof String key)) {
                continue;
            }
            if (DomainValues.isConstant(kv.get(i + 1))) {
                continue; // constant value: one series, whatever the key is called
            }
            String canonical = aliases.getOrDefault(key, key);
            Optional<String> valueKey = ctx.domain.keyOf(kv.get(i + 1));
            boolean high = ctx.policy.highCardinality(canonical) || valueKey.map(ctx.policy::highCardinality).orElse(false);
            boolean pii = ctx.domain.isPii(kv.get(i + 1));
            int line = Pos.line(l);
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
