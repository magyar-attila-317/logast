package dev.logast.rules;

import dev.logast.model.CatchInfo;
import dev.logast.model.MethodInfo;
import dev.logast.parse.Pos;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.List;
import java.util.Set;

/**
 * CATCH_ALL_DEFAULT (errors pack, report): {@code catch (Exception e) { … return null; }} (or Optional.empty(), an
 * empty collection, 0, false, "") is a silent failure: the caller cannot tell "it failed" from "there is nothing",
 * whether or not the catch logs. Only broad catches (Exception, RuntimeException, Throwable): a specific exception
 * mapped to a default is usually a decision.
 *
 * <p>The Reactor form (#14, #91 item 8): {@code onErrorReturn(value)}, {@code onErrorComplete()} and
 * {@code onErrorResume(e -> Mono.just(..) / Mono.empty() / Flux.empty())} without an error type or predicate turn every
 * error into a default value or an empty result. In WebFlux the request then ends as a success: the server
 * observation records an error only for an exception that reaches it (Spring Framework reference › Observability ›
 * HTTP Server instrumentation, reactive applications).
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
                ctx.orderNth(Rules.CATCH_ALL_DEFAULT, m, Pos.line(r), "catch-all-default")
                        .title("catch (" + c.typeNames() + ") returns " + r.getExpression().printTrimmed() + ": the caller cannot "
                                + "tell a failure from an empty result")
                        .suggestion("Let the exception through (or wrap it), catch only the exceptions that really mean "
                                + "\"nothing there\", or return a result type that carries the failure.")
                        .doneWhen("the catch no longer returns a default value (null, 0, false, \"\", an empty collection or "
                                + "Optional), or catches something narrower than Exception, RuntimeException or Throwable")
                        .about(m.file().relPath(), c.clause);
            }
            if (m.decl().getBody() != null) {
                reactive(m);
            }
        }
    }

    private static final Set<String> PUBLISHERS = Set.of("reactor.core.publisher.Mono", "reactor.core.publisher.Flux");
    private static final Set<String> FALLBACKS = Set.of("just", "empty", "justOrEmpty", "fromIterable");

    /** Unfiltered onErrorReturn / onErrorComplete / onErrorResume to a fallback in a Reactor pipeline. */
    private void reactive(MethodInfo m) {
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration c, Integer p) {
                return c;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                String fallback = swallowed(mi);
                if (fallback != null && !ctx.ignored(m.file(), Pos.line(mi), Rules.CATCH_ALL_DEFAULT)) {
                    ctx.orderNth(Rules.CATCH_ALL_DEFAULT, m, Pos.line(mi), "catch-all-default")
                            .title(mi.getSimpleName() + "(" + fallback + ") turns every error of the pipeline into "
                                    + (mi.getSimpleName().equals("onErrorComplete") ? "an empty result" : "a default value")
                                    + ": the subscriber cannot tell a failure from a result, and a WebFlux request ends as a success")
                            .suggestion("Let the error through (or map it to an exception the error handling answers), recover only "
                                    + "from the errors that really mean \"nothing there\" (" + mi.getSimpleName()
                                    + "(SomeException.class, ..)), or log it with the cause before falling back.")
                            .doneWhen("the operator names the error type or predicate it recovers from, or the pipeline lets "
                                    + "the error through")
                            .about(m.file().relPath(), mi);
                }
                return super.visitMethodInvocation(mi, p);
            }
        }.visit(m.decl().getBody(), 0);
    }

    /** What an unfiltered error operator falls back to, or null when it does not swallow every error. */
    private static String swallowed(J.MethodInvocation mi) {
        if (mi.getMethodType() == null || !PUBLISHERS.contains(mi.getMethodType().getDeclaringType().getFullyQualifiedName())) {
            return null;
        }
        List<Expression> args = mi.getArguments().stream().filter(a -> !(a instanceof J.Empty)).toList();
        switch (mi.getSimpleName()) {
            case "onErrorComplete":
                return args.isEmpty() ? "" : null;
            case "onErrorReturn":
                return args.size() == 1 ? args.getFirst().printTrimmed() : null;
            case "onErrorResume":
                if (args.size() != 1 || !(args.getFirst() instanceof J.Lambda l)) {
                    return null;
                }
                J body = l.getBody();
                if (body instanceof J.Block b) {
                    body = b.getStatements().size() == 1 && b.getStatements().getFirst() instanceof J.Return r ? r.getExpression() : null;
                }
                return body instanceof J.MethodInvocation call && FALLBACKS.contains(call.getSimpleName()) && call.getMethodType() != null
                        && PUBLISHERS.contains(call.getMethodType().getDeclaringType().getFullyQualifiedName())
                        ? args.getFirst().printTrimmed() : null;
            default:
                return null;
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
