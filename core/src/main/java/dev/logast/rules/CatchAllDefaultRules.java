package dev.logast.rules;

import dev.logast.model.CatchInfo;
import dev.logast.model.MethodInfo;
import dev.logast.parse.Pos;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.Set;

/**
 * CATCH_ALL_DEFAULT (errors pack, report): {@code catch (Exception e) { … return null; }} (or Optional.empty(), an
 * empty collection, 0, false, "") is a silent failure: the caller cannot tell "it failed" from "there is nothing",
 * whether or not the catch logs. Only broad catches (Exception, RuntimeException, Throwable): a specific exception
 * mapped to a default is usually a decision.
 */
final class CatchAllDefaultRules {
    private static final Set<String> BROAD = Set.of("java.lang.Exception", "java.lang.RuntimeException", "java.lang.Throwable");
    private static final Set<String> EMPTY_FACTORIES = Set.of("empty", "emptyList", "emptySet", "emptyMap", "of");

    private final RuleContext ctx;

    CatchAllDefaultRules(RuleContext ctx) {
        this.ctx = ctx;
    }

    void run() {
        if (!ctx.enabled(Rules.CATCH_ALL_DEFAULT)) {
            return;
        }
        for (MethodInfo m : ctx.methods()) {
            for (CatchInfo c : m.facts().catches) {
                if (c.types.stream().noneMatch(t -> t instanceof JavaType.FullyQualified fq && BROAD.contains(fq.getFullyQualifiedName()))) {
                    continue;
                }
                J.Return r = defaultReturn(c.clause.getBody());
                if (r == null || ctx.ignored(m.file(), Pos.line(r), Rules.CATCH_ALL_DEFAULT)) {
                    continue;
                }
                ctx.order(Rules.CATCH_ALL_DEFAULT, m, Pos.line(r), "catch-all-default|" + Pos.line(c.clause))
                        .title("catch (" + c.typeNames() + ") returns " + r.getExpression().printTrimmed() + ": the caller cannot "
                                + "tell a failure from an empty result")
                        .suggestion("Let the exception through (or wrap it), catch only the exceptions that really mean "
                                + "\"nothing there\", or return a result type that carries the failure.");
            }
        }
    }

    /** A return of a default value directly in the catch body (not inside a lambda or local class). */
    private static J.Return defaultReturn(J.Block body) {
        J.Return[] found = {null};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.Lambda visitLambda(J.Lambda l, Integer p) {
                return l;
            }

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration c, Integer p) {
                return c;
            }

            @Override
            public J.Return visitReturn(J.Return r, Integer p) {
                if (found[0] == null && r.getExpression() != null && isDefault(r.getExpression())) {
                    found[0] = r;
                }
                return r;
            }
        }.visit(body, 0);
        return found[0];
    }

    private static boolean isDefault(Expression e) {
        if (e instanceof J.Literal l) {
            Object v = l.getValue();
            return v == null || Boolean.FALSE.equals(v) || "".equals(v) || v instanceof Number n && n.doubleValue() == 0;
        }
        if (e instanceof J.MethodInvocation mi && (mi.getArguments().isEmpty() || mi.getArguments().getFirst() instanceof J.Empty)
                && EMPTY_FACTORIES.contains(mi.getSimpleName()) && mi.getMethodType() != null) {
            String owner = mi.getMethodType().getDeclaringType().getFullyQualifiedName();
            return owner.startsWith("java.util.") || owner.equals("java.util.Optional");
        }
        if (e instanceof J.NewClass nc && (nc.getArguments().isEmpty() || nc.getArguments().getFirst() instanceof J.Empty)) {
            return TypeUtils.isAssignableTo("java.util.Collection", nc.getType()) || TypeUtils.isAssignableTo("java.util.Map", nc.getType());
        }
        return false;
    }
}
