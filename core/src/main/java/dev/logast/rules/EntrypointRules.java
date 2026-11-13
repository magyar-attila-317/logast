package dev.logast.rules;

import dev.logast.graph.CallGraph;
import dev.logast.model.Entrypoint;
import dev.logast.model.Level;
import dev.logast.model.MethodInfo;
import dev.logast.orders.Edit;
import dev.logast.orders.Slot;
import dev.logast.parse.Pos;
import dev.logast.parse.SourceFileInfo;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;

import java.util.*;

/**
 * Entrypoint-level fixes. One order per entrypoint wraps the body in an {@code ObservedContext} (when the
 * framework does not observe it) and/or an {@code MdcScope} with the domain keys its parameters carry, and
 * adds an entry log when nothing below the entrypoint logs at INFO or above.
 */
final class EntrypointRules {
    private final RuleContext ctx;

    EntrypointRules(RuleContext ctx) {
        this.ctx = ctx;
    }

    void run() {
        for (Entrypoint ep : ctx.a.entrypoints) {
            if (ep.kind() == Entrypoint.Kind.BATCH || ep.method().owner().fqn().startsWith(ctx.a.supportPackage + ".")) {
                continue; // wrapped at runtime by logging-support's runner post-processor
            }
            entrypoint(ep);
        }
    }

    private void entrypoint(Entrypoint ep) {
        MethodInfo m = ep.method();
        SourceFileInfo f = m.file();
        String text = f.text();
        int line = m.line();

        boolean observed = ep.observedByFramework()
                || ep.kind() == Entrypoint.Kind.KAFKA && ctx.kafkaListenerObservationPlanned;
        boolean notObserved = !observed && ctx.enabled(Rules.ENTRYPOINT_NOT_OBSERVED)
                && !ctx.ignored(f, line, Rules.ENTRYPOINT_NOT_OBSERVED) && !alreadyObserved(m);
        List<DomainValues.Value> missing = ctx.enabled(Rules.MISSING_DOMAIN_MDC)
                && !ctx.ignored(f, line, Rules.MISSING_DOMAIN_MDC) && !ctx.legacyMdc(m)
                ? ctx.domain.fromVars(params(m)).stream()
                .filter(v -> !ctx.a.paths.mdcInside(m).contains(v.key())).toList()
                : List.of();
        boolean readEndpoint = ep.kind() == Entrypoint.Kind.HTTP && (ep.label().startsWith("GET ") || ep.label().startsWith("HEAD "));
        boolean silent = !readEndpoint && ctx.enabled(Rules.ENTRYPOINT_SILENT) && !ctx.ignored(f, line, Rules.ENTRYPOINT_SILENT)
                && !logsBelow(m, Level.parse(ctx.policy.logging().minEntrypointLevel()));
        if (!notObserved && missing.isEmpty() && !silent) {
            return;
        }

        String rule = notObserved ? Rules.ENTRYPOINT_NOT_OBSERVED
                : !missing.isEmpty() ? Rules.MISSING_DOMAIN_MDC : Rules.ENTRYPOINT_SILENT;
        OrderBuilder b = ctx.order(rule, m, line, "entry");
        if (!missing.isEmpty()) {
            b.fix(Rules.MISSING_DOMAIN_MDC);
        }
        if (silent) {
            b.fix(Rules.ENTRYPOINT_SILENT);
            ctx.silentPlanned.add(m.key());
        }
        b.title(title(ep, notObserved, missing, silent));

        int[] inner = m.decl().getBody() == null ? null : JavaEdits.blockInner(text, m.decl().getBody());
        if (inner == null) {
            b.suggestion("Method body layout not supported for automatic wrapping; wrap it manually.");
            return;
        }
        String unit = Text.indentUnit(text);
        String indent = Text.indentAt(text, Pos.start(m.decl().getBody())) + unit;
        String body = text.substring(inner[0], inner[1]);
        Set<String> taken = ctx.taken(m, inner[0]);

        StringBuilder top = new StringBuilder();
        List<Edit> extra = new ArrayList<>();
        if (silent) {
            JavaEdits.LoggerPlan logger = ctx.logger(m.owner());
            if (logger.name() == null) {
                b.suggestion("Cannot add a logger to " + m.owner().simpleName() + " (interface/enum).");
                silent = false;
            } else {
                extra.addAll(logger.edits());
                top.append(logger.name()).append('.').append(ctx.policy.logging().entryLevel()).append("(\"{{MSG}}\");\n");
                b.slot("MSG", new Slot("Entry log for " + ep.label() + " (" + m.label()
                        + "): what is starting, in domain terms, e.g. 'Order shipment processing started'.",
                        List.of(), ctx.glossary.terms(), null,
                        ctx.phrasing.canonicalOf("started") == null ? null : "started", ctx.phrasing.canonicalOf("started")));
            }
        }

        String replacement;
        if (notObserved) {
            List<String> chain = new ArrayList<>();
            for (DomainValues.Value v : missing) {
                chain.add(".mdc(\"" + v.key() + "\", " + v.expr() + ")");
            }
            if (ep.kind() == Entrypoint.Kind.KAFKA || ep.kind() == Entrypoint.Kind.JMS || ep.kind() == Entrypoint.Kind.RABBIT
                    || ep.kind() == Entrypoint.Kind.WEBSOCKET || ep.kind() == Entrypoint.Kind.HTTP) {
                chain.add(".debugOnSuccess()"); // one per message/request: "completed" at DEBUG; failures stay ERROR
            }
            if (ep.kind() == Entrypoint.Kind.EVENT && !m.hasAnnotation("org.springframework.scheduling.annotation.Async")
                    && !m.owner().decl().getLeadingAnnotations().stream().anyMatch(a -> a.getSimpleName().equals("Async"))) {
                // runs in the publisher's call: the exception goes back to code that logs it; one failure = one ERROR
                chain.add(".failureLoggedByCaller()");
            }
            replacement = observedWrap(m, observationName(m), chain, (top.isEmpty() ? "" : indent + unit + top) + Text.reindent(body, unit),
                    indent, unit, taken, ctx.supportHasRun());
            extra.addAll(JavaEdits.imports(f, ctx.support("ObservedContext")));
            ctx.dependOnStarter(b);
        } else if (!missing.isEmpty()) {
            String var = unique("mdc", taken);
            replacement = indent + "try (var " + var + " = " + mdcScope(missing) + ") {\n"
                    + (top.isEmpty() ? "" : indent + unit + top)
                    + Text.reindent(body, unit)
                    + indent + "}\n";
            extra.addAll(JavaEdits.imports(f, ctx.support("MdcScope")));
            ctx.dependOnStarter(b);
        } else {
            replacement = null;
            b.edit(Edit.insert(f.relPath(), inner[0], indent + top));
        }
        if (replacement != null) {
            ctx.wrappedMethods.add(m.key());
            b.edit(Edit.replace(f.relPath(), inner[0], inner[1], replacement));
        }
        b.edits(extra);
        b.context(ctx.context(m, inner[0], Text.oneLine(text.substring(Pos.start(m.decl()), Math.min(inner[0] + 200, text.length())), 200), null));
        if (!missing.isEmpty()) {
            ctx.plannedEntryKeys.put(m.key(), new TreeSet<>(missing.stream().map(DomainValues.Value::key).toList()));
            ctx.entryOrders.put(m.key(), b.id());
        }
    }

    /**
     * The body inside an ObservedContext: {@code ObservedContext.entrypoint(name)…run(() -> { body });} (or
     * {@code return …call(…)} for a result). Falls back to the try/catch/finally form where a lambda would not compile:
     * a parameter reassigned in the body (not effectively final), or several checked exceptions declared.
     *
     * @param inner the body, already indented one level deeper than {@code indent}
     */
    static String observedWrap(MethodInfo m, String name, List<String> chain, String inner, String indent, String unit, Set<String> taken,
                               boolean runAvailable) {
        String head = "ObservedContext.entrypoint(\"" + name + "\")";
        String cont = indent + unit + unit;
        if (runAvailable && lambdaFits(m)) {
            boolean result = !(m.decl().getReturnTypeExpression() instanceof J.Primitive p && p.getType() == JavaType.Primitive.Void);
            StringBuilder sb = new StringBuilder(indent).append(result ? "return " : "").append(head);
            chain.forEach(c -> sb.append('\n').append(cont).append(c));
            sb.append('\n').append(cont).append(result ? ".call(() -> {\n" : ".run(() -> {\n");
            sb.append(Text.reindent(inner, unit + unit));
            sb.append(cont).append("});\n");
            return sb.toString();
        }
        String obs = unique("observed", taken);
        String err = unique("t", taken);
        StringBuilder header = new StringBuilder("ObservedContext " + obs + " = " + head);
        chain.forEach(c -> header.append('\n').append(cont).append(c));
        return indent + header + ".start();\n"
                + indent + "try (" + obs + ") {\n"
                + inner
                + indent + "} catch (Throwable " + err + ") {\n"
                + indent + unit + obs + ".error(" + err + ");\n"
                + indent + unit + "throw " + err + ";\n"
                + indent + "} finally {\n"
                + indent + unit + obs + ".stop();\n"
                + indent + "}\n";
    }

    private static boolean lambdaFits(MethodInfo m) {
        if (m.decl().getThrows() != null && m.decl().getThrows().size() > 1 || m.decl().getBody() == null) {
            return false;
        }
        Set<String> params = params(m).keySet();
        boolean[] reassigned = {false};
        new org.openrewrite.java.JavaIsoVisitor<Integer>() {
            private void check(Expression target) {
                if (target instanceof J.Identifier id && params.contains(id.getSimpleName())) {
                    reassigned[0] = true;
                }
            }

            @Override
            public J.Assignment visitAssignment(J.Assignment a, Integer p) {
                check(a.getVariable());
                return super.visitAssignment(a, p);
            }

            @Override
            public J.AssignmentOperation visitAssignmentOperation(J.AssignmentOperation a, Integer p) {
                check(a.getVariable());
                return super.visitAssignmentOperation(a, p);
            }

            @Override
            public J.Unary visitUnary(J.Unary u, Integer p) {
                if (u.getOperator().isModifying()) {
                    check(u.getExpression());
                }
                return super.visitUnary(u, p);
            }
        }.visit(m.decl().getBody(), 0);
        return !reassigned[0];
    }

    static String mdcScope(List<DomainValues.Value> values) {
        // one call with all pairs: values are evaluated before MDC is touched (no half-open scope on exceptions)
        return "MdcScope.of(" + String.join(", ", values.stream().map(v -> "\"" + v.key() + "\", " + v.expr()).toList()) + ")";
    }

    private String title(Entrypoint ep, boolean notObserved, List<DomainValues.Value> missing, boolean silent) {
        List<String> parts = new ArrayList<>();
        if (notObserved) {
            parts.add("no Observation/trace is started");
        }
        if (!missing.isEmpty()) {
            parts.add("MDC lacks " + String.join(", ", missing.stream().map(DomainValues.Value::key).toList()));
        }
        if (silent) {
            parts.add("its own flow logs nothing at INFO (shared code deeper down and catch blocks do not count)");
        }
        return ep.label() + ": " + String.join("; ", parts);
    }

    private static Map<String, JavaType> params(MethodInfo m) {
        Map<String, JavaType> out = new LinkedHashMap<>();
        for (Statement s : m.decl().getParameters()) {
            if (s instanceof J.VariableDeclarations vd) {
                vd.getVariables().forEach(v -> out.put(v.getSimpleName(), v.getType()));
            }
        }
        return out;
    }

    private static boolean alreadyObserved(MethodInfo m) {
        String body = m.decl().getBody() == null ? "" : m.file().slice(Pos.start(m.decl().getBody()), Pos.end(m.decl().getBody()));
        return body.contains("ObservedContext.") || body.contains("Observation.start(") || body.contains("Observation.createNotStarted(")
                || m.hasAnnotation("io.micrometer.observation.annotation.Observed");
    }

    /** How deep a log may sit and still count as the entrypoint's own: the endpoint, its service, one level below. */
    static final int OWN_FLOW_DEPTH = 2;

    /**
     * Whether the entrypoint's own flow logs: a line at {@code min} or above on the normal path (not in a catch) of
     * the entrypoint or of a method at most {@link #OWN_FLOW_DEPTH} calls below it. Lines deeper down sit in shared
     * code (event publishers, audit, helpers) that many flows reach: they don't tell which flow ran.
     */
    private boolean logsBelow(MethodInfo root, Level min) {
        Deque<Map.Entry<MethodInfo, Integer>> queue = new ArrayDeque<>(List.of(Map.entry(root, 0)));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            var cur = queue.poll();
            MethodInfo m = cur.getKey();
            if (!seen.add(m.key())) {
                continue;
            }
            if (m.facts().logs.stream().anyMatch(l -> l.level().compareTo(min) >= 0 && l.inCatch() == null)) {
                return true;
            }
            if (cur.getValue() < OWN_FLOW_DEPTH) {
                for (CallGraph.Edge e : ctx.a.graph.out(m)) {
                    queue.add(Map.entry(e.to(), cur.getValue() + 1));
                }
            }
        }
        return false;
    }

    private static String observationName(MethodInfo m) {
        return Text.kebab(m.owner().simpleName()) + "." + Text.kebab(m.name());
    }

    static String unique(String base, Set<String> taken) {
        String name = base;
        for (int i = 2; taken.contains(name); i++) {
            name = base + i;
        }
        return name;
    }
}
