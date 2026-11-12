package dev.logast.rules;

import dev.logast.model.Entrypoint;
import dev.logast.model.MdcOp;
import dev.logast.policy.Policy;
import dev.logast.model.MethodInfo;
import dev.logast.orders.Edit;
import dev.logast.parse.Pos;
import dev.logast.parse.SourceFileInfo;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;

import java.util.*;

/**
 * MDC_NOT_CLOSEABLE: every {@code MDC.put} becomes {@code try (var mdc = MdcScope.of(k, v)) {...}} covering the
 * statements up to its matching remove (sibling statement or finally of a following try) or to the block end.
 * MANUAL_TRACE_MDC: put/remove of trace keys that OpenTelemetry already maintains are deleted.
 */
final class MdcRules {
    private final RuleContext ctx;
    private final Set<String> ambient;
    /** try statements already turned into one order (all their puts are handled together). */
    private final Set<J.Try> handledTries = Collections.newSetFromMap(new IdentityHashMap<>());

    MdcRules(RuleContext ctx) {
        this.ctx = ctx;
        this.ambient = new HashSet<>(ctx.policy.mdc().ambientKeys());
    }

    void run() {
        for (MethodInfo m : ctx.methods()) {
            if (!m.facts().mdc.isEmpty()) {
                method(m);
                clears(m);
                nestedKeys(m);
            }
        }
    }

    /**
     * MDC_CLEAR: {@code MDC.clear()} not already part of a converted put/clear block. It removes every key on the thread,
     * the caller's included (trace ids, request context), in the middle of a flow.
     */
    private void clears(MethodInfo m) {
        if (!ctx.enabled(Rules.MDC_CLEAR)) {
            return;
        }
        for (MdcOp op : m.facts().mdc) {
            if (op.kind() != MdcOp.Kind.CLEAR) {
                continue;
            }
            J.Try owner = op.block() == null ? null : tryWithFinally(m, op.block());
            if (owner != null && handledTries.contains(owner)) {
                continue;   // the MDC_NOT_CLOSEABLE order already replaces it
            }
            int line = Pos.line(op.call());
            if (ctx.ignored(m.file(), line, Rules.MDC_CLEAR)) {
                continue;
            }
            ctx.order(Rules.MDC_CLEAR, m, line, "clear" + line)
                    .title("MDC.clear() removes every MDC key on this thread, including the caller's (trace ids, request "
                            + "context): later logs of the same flow lose their context")
                    .suggestion("Scope what this code sets with try (var mdc = MdcScope.of(..)) and drop the clear(); a thread "
                            + "hand-off restores the previous map (ContextPropagation) instead of clearing.")
                    .context(ctx.context(m, Pos.start(op.call()), Text.oneLine(op.call().printTrimmed(), 160), null));
        }
    }

    /** The try whose finally block is {@code block}. */
    private static J.Try tryWithFinally(MethodInfo m, J.Block block) {
        J.Try[] found = new J.Try[1];
        new org.openrewrite.java.JavaIsoVisitor<Integer>() {
            @Override
            public J.Try visitTry(J.Try t, Integer p) {
                if (t.getFinally() == block) {
                    found[0] = t;
                }
                return super.visitTry(t, p);
            }
        }.visit(m.decl().getBody(), 0);
        return found[0];
    }

    /**
     * MDC_NESTED_KEY: the method scopes a key that every path into it already has in MDC. Two owners of one key: the
     * inner value hides the outer one, and the code that set it first no longer decides it.
     */
    private void nestedKeys(MethodInfo m) {
        if (!ctx.enabled(Rules.MDC_NESTED_KEY) || !ctx.a.paths.reachable(m)) {
            return;
        }
        Set<String> atEntry = ctx.a.paths.mdcAtEntry(m);
        Set<String> seen = new HashSet<>();
        for (MdcOp op : m.facts().mdc) {
            if ((op.kind() == MdcOp.Kind.PUT || op.kind() == MdcOp.Kind.PUT_CLOSEABLE) && op.key() != null
                    && atEntry.contains(op.key()) && !ambient.contains(op.key()) && seen.add(op.key())) {
                int line = Pos.line(op.call());
                if (ctx.ignored(m.file(), line, Rules.MDC_NESTED_KEY)) {
                    continue;
                }
                ctx.order(Rules.MDC_NESTED_KEY, m, line, "nested|" + op.key())
                        .title("MDC key '" + op.key() + "' is already set on every path into " + m.label()
                                + ": a nested scope for the same key")
                        .suggestion("Drop this scope if the value is the same; if it differs, use a distinct key "
                                + "(e.g. source." + op.key() + ") so both stay visible.")
                        .context(ctx.context(m, Pos.start(op.call()), Text.oneLine(op.call().printTrimmed(), 160), null));
            }
        }
    }

    private void method(MethodInfo m) {
        SourceFileInfo f = m.file();
        String text = f.text();
        List<MdcOp> ambientOps = m.facts().mdc.stream()
                .filter(op -> op.key() != null && ambient.contains(op.key()) && op.block() != null
                        && (op.kind() == MdcOp.Kind.PUT || op.kind() == MdcOp.Kind.REMOVE))
                .toList();
        Set<MdcOp> coveredAmbient = Collections.newSetFromMap(new IdentityHashMap<>());
        List<int[]> claimed = new ArrayList<>();

        if (ctx.enabled(Rules.MDC_NOT_CLOSEABLE)) {
            int ordinal = 0;
            for (MdcOp op : m.facts().mdc) {
                if (op.kind() != MdcOp.Kind.PUT || op.key() == null || ambient.contains(op.key()) || op.block() == null
                        || op.inFinally()) {
                    continue;
                }
                int line = Pos.line(op.call());
                int start = Pos.start(op.call());
                if (claimed.stream().anyMatch(r -> r[0] <= start && start < r[1]) || ctx.ignored(f, line, Rules.MDC_NOT_CLOSEABLE)) {
                    continue;
                }
                int[] region = notCloseable(m, op, ambientOps, coveredAmbient, ordinal++);
                if (region != null) {
                    claimed.add(region);
                }
            }
        }

        if (ctx.enabled(Rules.MDC_KEY_ALIAS)) {
            Map<String, String> aliases = ctx.policy.mdcAliases();
            int n = 0;
            for (MdcOp op : m.facts().mdc) {
                String canonical = ctx.policy.canonicalMdcKey(op.key());
                int st = Pos.start(op.call());
                if (canonical == null || claimed.stream().anyMatch(r -> r[0] <= st && st < r[1])) {
                    continue;
                }
                J.Literal lit = op.call().getArguments().stream()
                        .filter(a -> a instanceof J.Literal l && op.key().equals(l.getValue()))
                        .map(J.Literal.class::cast).findFirst().orElse(null);
                int line = Pos.line(op.call());
                if (lit == null || ctx.ignored(f, line, Rules.MDC_KEY_ALIAS)) {
                    continue;
                }
                ctx.order(Rules.MDC_KEY_ALIAS, m, line, "alias" + n++)
                        .title(aliases.containsKey(op.key()) ? "MDC key '" + op.key() + "' is an alias of '" + canonical + "' (vocabulary)"
                                : "MDC key '" + op.key() + "' is not spelled in keyStyle " + ctx.policy.logging().keyStyle() + ": '" + canonical + "'")
                        .edit(Edit.replace(f.relPath(), Pos.start(lit), Pos.end(lit), "\"" + canonical + "\""))
                        .context(ctx.context(m, st, Text.oneLine(op.call().printTrimmed(), 160), null));
            }
        }

        if (ctx.enabled(Rules.MANUAL_TRACE_MDC)) {
            List<MdcOp> remaining = ambientOps.stream().filter(op -> !coveredAmbient.contains(op)).toList();
            if (!remaining.isEmpty() && !ctx.ignored(f, Pos.line(remaining.getFirst().call()), Rules.MANUAL_TRACE_MDC)) {
                MdcOp first = remaining.stream().filter(op -> op.kind() == MdcOp.Kind.PUT).findFirst().orElse(remaining.getFirst());
                OrderBuilder b = ctx.order(Rules.MANUAL_TRACE_MDC, m, Pos.line(first.call()), "trace")
                        .title("Sets " + first.key() + " in MDC by hand; OpenTelemetry/Micrometer Tracing maintains it")
                        .suggestion("Trace context comes from W3C traceparent propagation; delete the manual MDC handling.");
                remaining.forEach(op -> b.edit(JavaEdits.deleteStatement(f, op.call())));
                b.context(ctx.context(m, Pos.start(first.call()), Text.oneLine(text.substring(Pos.start(first.call()), JavaEdits.statementEnd(text, first.call())), 160), null));
            }
        }
    }

    /** @return the replaced range, or null when no edit was produced */
    private int[] notCloseable(MethodInfo m, MdcOp op, List<MdcOp> ambientOps, Set<MdcOp> coveredAmbient, int ordinal) {
        SourceFileInfo f = m.file();
        String text = f.text();
        int line = Pos.line(op.call());
        List<Statement> stmts = op.block().getStatements();
        int i = indexOf(stmts, op.call());
        OrderBuilder b = ctx.order(Rules.MDC_NOT_CLOSEABLE, m, line, "put" + ordinal);
        b.context(ctx.context(m, Pos.start(op.call()), Text.oneLine(text.substring(Pos.start(op.call()), JavaEdits.statementEnd(text, op.call())), 160), null));
        int[] pLines = JavaEdits.statementLines(text, op.call());
        J.Try enclosing = enclosingTry(m, op.block());
        if (i >= 0 && enclosing != null && enclosing.getFinally() != null && enclosing.getFinally().getStatements().stream()
                .anyMatch(fs -> isMdc(fs, "clear", null) || isMdc(fs, "remove", op.key()))) {
            if (!handledTries.add(enclosing)) {
                ctx.drop(b);
                return null;
            }
            return finallyScoped(m, op, b, enclosing, ambientOps, coveredAmbient);
        }
        if (i < 0 || pLines[0] != Text.lineStart(text, Pos.start(op.call()))) {
            b.title("MDC.put(\"" + op.key() + "\") is not scoped").suggestion("Use try (var mdc = MdcScope.of(...)) { ... }.");
            return null;
        }

        String mode = "leak";
        int j = -1;
        for (int k = i + 1; k < stmts.size(); k++) {
            Statement s = stmts.get(k);
            if (isMdc(s, "remove", op.key())) {
                mode = "remove";
                j = k;
                break;
            }
            if (s instanceof J.Try t && t.getFinally() != null
                    && t.getFinally().getStatements().stream().anyMatch(fs -> isMdc(fs, "remove", op.key()))) {
                mode = "try";
                j = k;
                break;
            }
        }
        if (mode.equals("leak") && i == stmts.size() - 1) {
            b.severity("high").title("MDC.put(\"" + op.key() + "\") leaks: never removed")
                    .suggestion("The key outlives this method on a pooled thread. Scope it with MdcScope around the work it describes.");
            return null;
        }
        b.title(mode.equals("leak") ? "MDC.put(\"" + op.key() + "\") leaks into later work on this thread (never removed)"
                : "MDC.put/remove pair for \"" + op.key() + "\": use a try-with-resources MdcScope (restores previous value)");
        if (mode.equals("leak")) {
            b.severity("high");
        }

        int regionEnd = mode.equals("leak") ? stmts.size() : j;
        List<int[]> cuts = new ArrayList<>();
        for (MdcOp a : ambientOps) {
            int s = Pos.start(a.call());
            if (s > Pos.start(op.call()) && within(stmts, i + 1, regionEnd, a.call(), mode.equals("try") ? stmts.get(j) : null)) {
                cuts.add(JavaEdits.statementLines(text, a.call()));
                coveredAmbient.add(a);
                if (a.kind() == MdcOp.Kind.PUT) {
                    b.location(f.relPath(), Pos.line(a.call()), Rules.MANUAL_TRACE_MDC);
                }
            }
        }

        String unit = Text.indentUnit(text);
        String indent = Text.indentAt(text, Pos.start(op.call()));
        int from = pLines[1];
        String region;
        int replaceEnd;
        switch (mode) {
            case "remove" -> {
                int[] rl = JavaEdits.statementLines(text, stmts.get(j));
                region = Text.reindent(JavaEdits.cut(text, from, rl[0], cuts), unit);
                replaceEnd = rl[1];
            }
            case "try" -> {
                J.Try t = (J.Try) stmts.get(j);
                cuts.addAll(t.getFinally().getStatements().stream().filter(fs -> isMdc(fs, "remove", op.key()))
                        .map(fs -> JavaEdits.statementLines(text, fs)).toList());
                boolean finallyEmpties = t.getFinally().getStatements().stream()
                        .allMatch(fs -> isMdc(fs, "remove", op.key()) || cuts.stream().anyMatch(c -> c[0] <= Pos.start(fs) && Pos.start(fs) < c[1]));
                int tStart = Text.lineStart(text, Pos.start(t));
                replaceEnd = Text.nextLine(text, Pos.end(t) - 1);
                int[] bodyInner = JavaEdits.blockInner(text, t.getBody());
                if (finallyEmpties && t.getCatches().isEmpty() && t.getResources() == null && bodyInner != null) {
                    region = Text.reindent(JavaEdits.cut(text, from, tStart, cuts), unit)
                            + JavaEdits.cut(text, bodyInner[0], bodyInner[1], cuts);
                } else {
                    region = Text.reindent(JavaEdits.cut(text, from, replaceEnd, cuts), unit);
                }
            }
            default -> {
                Statement last = stmts.getLast();
                replaceEnd = Text.nextLine(text, JavaEdits.statementEnd(text, last) - 1);
                region = Text.reindent(JavaEdits.cut(text, from, replaceEnd, cuts), unit);
            }
        }
        if (!region.endsWith("\n")) {
            region += "\n";
        }
        List<Expression> args = op.call().getArguments();
        String var = EntrypointRules.unique("mdc", ctx.vars(m, Pos.start(op.call())).keySet());
        String canonical = ctx.policy.canonicalMdcKey(op.key());
        if (canonical != null) {
            b.fix(Rules.MDC_KEY_ALIAS);
        }
        String keySrc = canonical != null ? "\"" + canonical + "\"" : src(f, args.get(0));
        String replacement = indent + "try (var " + var + " = MdcScope.of(" + keySrc + ", " + src(f, args.get(1)) + ")) {\n"
                + region + indent + "}\n";
        b.edit(Edit.replace(f.relPath(), pLines[0], replaceEnd, replacement));
        b.edits(JavaEdits.imports(f, ctx.support("MdcScope")));
        if (!text.substring(0, pLines[0]).concat(text.substring(replaceEnd)).contains("MDC.")) {
            b.edit(removeImport(f, "org.slf4j.MDC"));
        }
        ctx.dependOnStarter(b);
        return new int[]{pLines[0], replaceEnd};
    }

    /**
     * {@code try { MDC.put(a); MDC.put(b); ...work } finally { MDC.clear(); }}: not a leak, but clear() also wipes keys
     * the caller set (request context, propagated context). Rewritten to one multi-key MdcScope when the block has no
     * other (dynamic) MDC writes and the try is plain; otherwise reported.
     */
    private int[] finallyScoped(MethodInfo m, MdcOp first, OrderBuilder b, J.Try t, List<MdcOp> ambientOps,
                                Set<MdcOp> coveredAmbient) {
        SourceFileInfo f = m.file();
        String text = f.text();
        boolean clears = t.getFinally().getStatements().stream().anyMatch(fs -> isMdc(fs, "clear", null));
        b.title("MDC set inside try/finally with " + (clears ? "MDC.clear(), which also wipes the caller's MDC context"
                : "MDC.remove()") + ": use one MdcScope (restores exactly what it changed)");
        List<Statement> body = t.getBody().getStatements();
        List<J.MethodInvocation> puts = new ArrayList<>();
        int k = 0;
        Map<J.MethodInvocation, String> keyOf = new IdentityHashMap<>();
        m.facts().mdc.forEach(o -> {
            if (o.key() != null) {
                keyOf.put(o.call(), o.key());
            }
        });
        while (k < body.size() && body.get(k) instanceof J.MethodInvocation mi && isMdc(mi, "put", null)
                && keyOf.containsKey(mi)) {   // literal or a resolved String constant
            puts.add(mi);
            k++;
        }
        boolean otherMdcWrites = m.facts().mdc.stream().anyMatch(o -> o.kind() == MdcOp.Kind.PUT && !puts.contains(o.call())
                && Pos.start(o.call()) > Pos.start(t) && Pos.start(o.call()) < Pos.end(t.getBody()));
        boolean finallyOnlyMdc = t.getFinally().getStatements().stream()
                .allMatch(fs -> isMdc(fs, "clear", null) || isMdc(fs, "remove", null));
        int[] inner = JavaEdits.blockInner(text, t.getBody());
        if (puts.isEmpty() || otherMdcWrites || !finallyOnlyMdc || !t.getCatches().isEmpty() || t.getResources() != null
                || inner == null) {
            if (clears) {
                b.severity("high").fix(Rules.MDC_CLEAR);
            }
            b.suggestion("Replace the puts with try (var mdc = MdcScope.of(k1, v1, k2, v2 ...)) and drop the finally cleanup"
                    + (otherMdcWrites ? "; the block also writes computed MDC keys, scope those explicitly too" : "")
                    + (clears ? ". MDC.clear() removes keys this method never set." : "."));
            return null;
        }
        Map<String, String> aliases = ctx.policy.mdcAliases();
        List<String> pairs = new ArrayList<>();
        for (J.MethodInvocation put : puts) {
            String key = keyOf.get(put);
            boolean literal = put.getArguments().getFirst() instanceof J.Literal;
            if (ambient.contains(key)) {
                b.location(f.relPath(), Pos.line(put), Rules.MANUAL_TRACE_MDC);
                ambientOps.stream().filter(o -> o.call() == put).forEach(coveredAmbient::add);
                continue;
            }
            String canonical = literal ? ctx.policy.canonicalMdcKey(key) : null;   // a constant keeps its name; rename it at its declaration
            String effective = canonical != null ? canonical : key;
            if (sameValueAlreadyInMdc(m, effective, put.getArguments().get(1))) {
                b.fix(Rules.MDC_NESTED_KEY);
                if (!ctx.a.paths.mdcAtEntry(m).contains(effective)) {
                    // only planned: the entrypoint fixes that add it must be applied first
                    for (Entrypoint ep : ctx.a.paths.entrypointsReaching(m)) {
                        String id = ctx.entryOrders.get(ep.method().key());
                        if (id != null) {
                            b.dependsOn(id);
                        }
                    }
                }
                continue;   // every caller already scoped this value: a second scope would only hide the first
            }
            if (canonical != null) {
                b.fix(Rules.MDC_KEY_ALIAS);
            }
            pairs.add((canonical != null ? "\"" + canonical + "\"" : src(f, put.getArguments().getFirst())) + ", " + src(f, put.getArguments().get(1)));
        }
        ambientOps.stream().filter(o -> Pos.start(o.call()) > Pos.start(t) && Pos.start(o.call()) < Pos.end(t)).forEach(coveredAmbient::add);
        int restStart = puts.isEmpty() ? inner[0] : Text.nextLine(text, JavaEdits.statementEnd(text, puts.getLast()) - 1);
        String rest = text.substring(restStart, inner[1]);
        rest = rest.replaceFirst("^(\\s*\n)+", "");
        String indent = Text.indentAt(text, Pos.start(t));
        String var = EntrypointRules.unique("mdc", ctx.vars(m, Pos.start(t)).keySet());
        String replacement = pairs.isEmpty()
                ? Text.dedent(rest, Text.indentUnit(text))
                : indent + "try (var " + var + " = MdcScope.of(" + String.join(", ", pairs) + ")) {\n" + rest + indent + "}\n";
        int from = Text.lineStart(text, Pos.start(t));
        int to = Text.nextLine(text, Pos.end(t) - 1);
        b.severity(clears ? "high" : "low");
        if (clears) {
            b.fix(Rules.MDC_CLEAR);
        }
        b.edit(Edit.replace(f.relPath(), from, to, replacement));
        b.edits(JavaEdits.imports(f, ctx.support("MdcScope")));
        if (!text.substring(0, from).concat(text.substring(to)).contains("MDC.")) {
            b.edit(removeImport(f, "org.slf4j.MDC"));
        }
        ctx.dependOnStarter(b);
        return new int[]{from, to};
    }

    /**
     * The key is in MDC on every path into {@code m}, and the value is the parameter that carries it (a source
     * parameter of that vocabulary key): the caller's scope already holds exactly this value.
     */
    private boolean sameValueAlreadyInMdc(MethodInfo m, String key, Expression value) {
        if (!ctx.a.paths.reachable(m) || !ctx.mdcAtEntry(m).contains(key) || !(value instanceof J.Identifier id)) {
            return false;
        }
        Policy.KeySource src = ctx.policy.mdcRegistry().get(key);
        boolean param = m.decl().getParameters().stream().anyMatch(p -> p instanceof J.VariableDeclarations vd
                && vd.getVariables().stream().anyMatch(v -> v.getSimpleName().equals(id.getSimpleName())));
        return param && src != null && src.params().contains(id.getSimpleName());
    }

    /** The try statement whose body is {@code block}, if any. */
    private static J.Try enclosingTry(MethodInfo m, J.Block block) {
        if (block == null) {
            return null;
        }
        J.Try[] found = new J.Try[1];
        new org.openrewrite.java.JavaIsoVisitor<Integer>() {
            @Override
            public J.Try visitTry(J.Try t, Integer p) {
                if (t.getBody() == block) {
                    found[0] = t;
                }
                return super.visitTry(t, p);
            }
        }.visit(m.decl(), 0);
        return found[0];
    }

    private static Edit removeImport(SourceFileInfo f, String fqn) {
        String text = f.text();
        int i = text.indexOf("import " + fqn + ";");
        return i < 0 ? null : Edit.replace(f.relPath(), i, Text.nextLine(text, i), "");
    }

    private static boolean within(List<Statement> stmts, int from, int to, J call, Statement tryStmt) {
        int s = Pos.start(call);
        for (int k = from; k < to; k++) {
            if (Pos.start(stmts.get(k)) <= s && s < Pos.end(stmts.get(k))) {
                return true;
            }
        }
        return tryStmt != null && Pos.start(tryStmt) <= s && s < Pos.end(tryStmt);
    }

    private static int indexOf(List<Statement> stmts, J call) {
        for (int k = 0; k < stmts.size(); k++) {
            if (stmts.get(k) == call) {
                return k;
            }
        }
        return -1;
    }

    /** {@code key == null} matches any key (and argument-less calls such as clear()). */
    private static boolean isMdc(Statement s, String method, String key) {
        return s instanceof J.MethodInvocation mi && mi.getSimpleName().equals(method)
                && mi.getMethodType() != null && "org.slf4j.MDC".equals(mi.getMethodType().getDeclaringType().getFullyQualifiedName())
                && (key == null || !mi.getArguments().isEmpty() && mi.getArguments().getFirst() instanceof J.Literal l && key.equals(l.getValue()));
    }

    private static String src(SourceFileInfo f, Expression e) {
        return f.slice(Pos.start(e), Pos.end(e));
    }
}
