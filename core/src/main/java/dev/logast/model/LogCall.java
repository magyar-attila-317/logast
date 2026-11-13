package dev.logast.model;

import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.TypeUtils;

import java.util.List;

/**
 * A call to an SLF4J logger.
 *
 * @param template the message argument (null for fluent calls without one)
 * @param args     arguments after the template, including a trailing throwable
 * @param inCatch  innermost catch clause whose body contains the call, if any
 * @param statement true when the call is a statement directly inside a block
 * @param loopBound 0 = not in a loop; -1 = in a loop over a collection of unknown size; N = bounded loop(s)
 *                  (enum values, List.of(..) literals, ...) running at most N times in total
 */
public record LogCall(J.MethodInvocation call, Level level, boolean fluent, Expression template,
                      List<Expression> args, CatchInfo inCatch, boolean statement, int loopBound, boolean offThread) {
    /** @param offThread runs in a lambda handed to another thread without MDC propagation (MDC is empty there) */
    public LogCall(J.MethodInvocation call, Level level, boolean fluent, Expression template, List<Expression> args,
                   CatchInfo inCatch, boolean statement, int loopBound) {
        this(call, level, fluent, template, args, inCatch, statement, loopBound, false);
    }


    public boolean inLoop() {
        return loopBound != 0;
    }

    /** In a loop whose size is unknown, or bounded but larger than {@code max}. */
    /** Loop over data from a query or stream (database results, file lines, Kafka batches). */
    public static final int LARGE = -2;
    /** Loop whose size cannot be told from the code. */
    public static final int UNKNOWN = -1;

    public boolean inLargeLoop(int max) {
        return loopBound < 0 || loopBound > max;
    }

    /** Over data from a query/stream, or a known size above {@code max}. */
    public boolean inDataLoop(int max) {
        return loopBound == LARGE || loopBound > max;
    }

    public boolean passesThrowable() {
        return args.stream().anyMatch(a -> TypeUtils.isAssignableTo("java.lang.Throwable", a.getType()));
    }

    public String loggerExpression() {
        return call.getSelect() == null ? null : call.getSelect().printTrimmed();
    }
}
