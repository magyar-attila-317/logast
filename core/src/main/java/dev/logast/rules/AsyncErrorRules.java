package dev.logast.rules;

import dev.logast.model.MethodInfo;
import dev.logast.orders.Edit;
import dev.logast.parse.Pos;
import dev.logast.parse.SourceFileInfo;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.TypeUtils;

import java.util.Set;

/**
 * ASYNC_ERROR_LOST (errors pack): a Future whose result is thrown away keeps its exception, and nobody ever reads it.
 * <ul>
 *   <li>a {@code CompletableFuture} statement with no {@code exceptionally / handle / whenComplete / join / get} in its
 *       chain: AUTO, a {@code whenComplete} that logs the failure (wording marked {@code // logast:wording});</li>
 *   <li>any other discarded Future ({@code executor.submit(…)}, an {@code @Async} method returning one): report.</li>
 * </ul>
 * ({@code @Async void} methods are fine: Spring logs their exceptions, with the caller's MDC through the TaskDecorator.)
 */
final class AsyncErrorRules {
    private static final Set<String> HANDLED = Set.of("exceptionally", "exceptionallyAsync", "exceptionallyCompose",
            "exceptionallyComposeAsync", "handle", "handleAsync", "whenComplete", "whenCompleteAsync", "join", "get",
            "orTimeout", "completeOnTimeout");

    private final RuleContext ctx;

    AsyncErrorRules(RuleContext ctx) {
        this.ctx = ctx;
    }

    void run() {
        if (!ctx.enabled(Rules.ASYNC_ERROR_LOST)) {
            return;
        }
        for (MethodInfo m : ctx.methods()) {
            if (m.decl().getBody() == null || m.owner().fqn().startsWith(ctx.a.supportPackage + ".")) {
                continue;
            }
            new JavaIsoVisitor<Integer>() {
                @Override
                public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration c, Integer p) {
                    return c;   // local and anonymous classes are their own methods
                }

                @Override
                public J.Block visitBlock(J.Block block, Integer p) {
                    for (var s : block.getStatements()) {
                        if (s instanceof J.MethodInvocation mi && TypeUtils.isAssignableTo("java.util.concurrent.Future", mi.getType())
                                && !handled(mi) && !logsItsOwnFailures(mi)) {
                            discarded(m, mi);
                        }
                    }
                    return super.visitBlock(block, p);
                }
            }.visit(m.decl().getBody(), 0);
        }
    }

    /** KafkaTemplate logs failed sends itself (its default LoggingProducerListener). */
    private static boolean logsItsOwnFailures(J.MethodInvocation mi) {
        return mi.getMethodType() != null
                && TypeUtils.isAssignableTo("org.springframework.kafka.core.KafkaOperations", mi.getMethodType().getDeclaringType());
    }

    /** Somewhere in the chain the failure is handled or the result awaited. */
    private static boolean handled(J.MethodInvocation mi) {
        for (Expression e = mi; e instanceof J.MethodInvocation call; e = call.getSelect()) {
            if (HANDLED.contains(call.getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    private void discarded(MethodInfo m, J.MethodInvocation mi) {
        SourceFileInfo f = m.file();
        int line = Pos.line(mi);
        if (ctx.ignored(f, line, Rules.ASYNC_ERROR_LOST)) {
            return;
        }
        boolean completable = TypeUtils.isAssignableTo("java.util.concurrent.CompletionStage", mi.getType());
        OrderBuilder b = ctx.orderNth(Rules.ASYNC_ERROR_LOST, m, line, "async-error")
                .title("The result of " + mi.getSimpleName() + "(…) is thrown away: if the work fails, its exception stays in "
                        + "the future and is never logged");
        JavaEdits.LoggerPlan logger = ctx.logger(m.owner());
        if (!completable || logger.name() == null) {
            b.suggestion("Use execute(…) for fire-and-forget work (an uncaught exception is then logged), or keep the Future "
                    + "and check it (get with a timeout).");
            return;
        }
        String text = f.text();
        String indent = Text.indentAt(text, Pos.start(mi));
        String unit = Text.indentUnit(text);
        String message = FamilyRules.classWords(m.owner().simpleName()) + " async work failed";
        // lambda parameters must not reuse a name of a variable in scope (JLS 15.27.2): `result` is a common local
        java.util.Set<String> taken = new java.util.HashSet<>(ctx.taken(m, Pos.start(mi)));
        String result = EntrypointRules.unique("result", taken);
        taken.add(result);
        String error = EntrypointRules.unique("error", taken);
        String handler = "\n" + indent + unit + unit + ".whenComplete((" + result + ", " + error + ") -> {\n"
                + indent + unit + unit + unit + "if (" + error + " != null) {\n"
                + indent + unit + unit + unit + unit + logger.name() + ".error(" + Text.javaString(message) + ", " + error + "); "
                + DbRules.WORDING_MARKER + "\n"
                + indent + unit + unit + unit + "}\n"
                + indent + unit + unit + "})";
        b.edit(Edit.insert(f.relPath(), Pos.end(mi), handler));
        b.edits(logger.edits());
        b.suggestion("Adds a whenComplete that logs the failure (ERROR, with the exception). The wording is marked "
                + DbRules.WORDING_MARKER + ".");
    }
}
