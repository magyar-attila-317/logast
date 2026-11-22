package dev.logast.rules;

import dev.logast.graph.CallGraph;
import dev.logast.model.MethodInfo;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;

import java.util.*;

/**
 * Whether a logged value is the value an MDC key holds, followed back through the code instead of guessed from its
 * name: the variable or accessor of the key's name ({@code runCode}, {@code summary.runCode()}), a local initialised
 * from one and never reassigned ({@code String code = runCode;}), or a parameter every caller passes one into (up to
 * {@link #DEPTH} calls up). Anything else (a lambda or method reference caller, a reassigned local, one caller passing
 * something else) is not the same value: the key stays in the message.
 */
final class ValueTrace {
    static final int DEPTH = 3;

    private final RuleContext ctx;

    ValueTrace(RuleContext ctx) {
        this.ctx = ctx;
    }

    boolean isKey(Expression e, String key, MethodInfo m) {
        return isKey(e, key, m, DEPTH, new HashSet<>());
    }

    private boolean isKey(Expression e, String key, MethodInfo m, int depth, Set<String> seen) {
        if (e == null) {
            return false;
        }
        Expression v = unwrap(e);
        if (LogCallRules.sameValue(v.printTrimmed(), key)) {
            return true;
        }
        if (!(v instanceof J.Identifier id) || depth == 0 || m.decl().getBody() == null) {
            return false;
        }
        String name = id.getSimpleName();
        Local local = local(m, name);
        if (local != null) {
            return local.assignments() == 0 && isKey(local.initializer(), key, m, depth - 1, seen);
        }
        int idx = paramIndex(m, name);
        if (idx < 0 || !seen.add(m.key() + "|" + idx) || assigned(m, name)) {
            return false;
        }
        List<CallGraph.Edge> callers = ctx.a.graph.in(m);
        if (callers.isEmpty()) {
            return false;
        }
        for (CallGraph.Edge c : callers) {
            if (!(c.site().tree() instanceof J.MethodInvocation mi) || !mi.getSimpleName().equals(m.name())) {
                return false;   // a method reference, an event, a constructor: no argument to follow
            }
            List<Expression> args = mi.getArguments().stream().filter(a -> !(a instanceof J.Empty)).toList();
            if (idx >= args.size() || !isKey(args.get(idx), key, c.from(), depth - 1, seen)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Where a value comes from: an expression it was computed by, in the method it sits in, with the parameter
     * declaration when the trail ends at a parameter nobody (no plain call) passes in, e.g. an entrypoint's.
     */
    record Source(Expression value, MethodInfo in, J.VariableDeclarations param) {
    }

    /**
     * The expressions {@code e} may hold, followed back as {@link #isKey} does: through a local initialised once and
     * never reassigned, and through a parameter to the argument every caller passes (up to {@link #DEPTH} calls up).
     * Where the trail cannot be followed further (a field, a reassigned local, a lambda caller) that expression is the
     * source.
     */
    List<Source> sources(Expression e, MethodInfo m) {
        List<Source> out = new ArrayList<>();
        sources(e, m, DEPTH, new HashSet<>(), out);
        return out;
    }

    private void sources(Expression e, MethodInfo m, int depth, Set<String> seen, List<Source> out) {
        Expression v = unwrap(e);
        if (!(v instanceof J.Identifier id) || depth == 0 || m.decl().getBody() == null) {
            out.add(new Source(v, m, null));
            return;
        }
        String name = id.getSimpleName();
        Local local = local(m, name);
        if (local != null) {
            if (local.assignments() == 0 && local.initializer() != null) {
                sources(local.initializer(), m, depth - 1, seen, out);
            } else {
                out.add(new Source(v, m, null));
            }
            return;
        }
        int idx = paramIndex(m, name);
        if (idx < 0) {
            out.add(new Source(v, m, null));   // a field
            return;
        }
        J.VariableDeclarations param = (J.VariableDeclarations) m.decl().getParameters().get(idx);
        List<CallGraph.Edge> callers = ctx.a.graph.in(m);
        if (callers.isEmpty() || !seen.add(m.key() + "|" + idx) || assigned(m, name)) {
            out.add(new Source(v, m, param));
            return;
        }
        for (CallGraph.Edge c : callers) {
            List<Expression> args = c.site().tree() instanceof J.MethodInvocation mi && mi.getSimpleName().equals(m.name())
                    ? mi.getArguments().stream().filter(a -> !(a instanceof J.Empty)).toList() : List.of();
            if (idx < args.size()) {
                sources(args.get(idx), c.from(), depth - 1, seen, out);
            } else {
                out.add(new Source(v, m, param));   // a method reference, an event, a constructor: no argument to follow
            }
        }
    }

    static Expression unwrap(Expression e) {
        Expression v = e;
        while (true) {
            if (v instanceof J.Parentheses<?> p && p.getTree() instanceof Expression inner) {
                v = inner;
            } else if (v instanceof J.TypeCast tc) {
                v = tc.getExpression();
            } else {
                return v;
            }
        }
    }

    private record Local(Expression initializer, int assignments) {
    }

    /** The method's local of that name: its initializer and how often it is assigned again. Null if none. */
    private static Local local(MethodInfo m, String name) {
        Expression[] init = {null};
        boolean[] found = {false};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable v, Integer p) {
                if (v.getSimpleName().equals(name) && !found[0]) {
                    found[0] = true;
                    init[0] = v.getInitializer();
                }
                return super.visitVariable(v, p);
            }
        }.visit(m.decl().getBody(), 0);
        return found[0] ? new Local(init[0], assignments(m, name)) : null;
    }

    private static boolean assigned(MethodInfo m, String name) {
        return assignments(m, name) > 0;
    }

    private static int assignments(MethodInfo m, String name) {
        int[] n = {0};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.Assignment visitAssignment(J.Assignment a, Integer p) {
                if (a.getVariable() instanceof J.Identifier id && id.getSimpleName().equals(name)) {
                    n[0]++;
                }
                return super.visitAssignment(a, p);
            }

            @Override
            public J.AssignmentOperation visitAssignmentOperation(J.AssignmentOperation a, Integer p) {
                if (a.getVariable() instanceof J.Identifier id && id.getSimpleName().equals(name)) {
                    n[0]++;
                }
                return super.visitAssignmentOperation(a, p);
            }
        }.visit(m.decl().getBody(), 0);
        return n[0];
    }

    private static int paramIndex(MethodInfo m, String name) {
        List<Statement> params = m.decl().getParameters();
        for (int i = 0; i < params.size(); i++) {
            if (params.get(i) instanceof J.VariableDeclarations vd
                    && vd.getVariables().stream().anyMatch(v -> v.getSimpleName().equals(name))) {
                return i;
            }
        }
        return -1;
    }
}
