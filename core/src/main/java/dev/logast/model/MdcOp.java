package dev.logast.model;

import org.openrewrite.java.tree.J;

/**
 * An {@code org.slf4j.MDC} call.
 *
 * @param key        literal key, or null when computed
 * @param block      enclosing block when the call is a direct statement, else null
 * @param inFinally  inside a finally block
 * @param inResource used as a try-with-resources resource
 */
public record MdcOp(Kind kind, String key, J.MethodInvocation call, J.Block block, boolean inFinally,
                    boolean inResource) {
    public enum Kind { PUT, PUT_CLOSEABLE, REMOVE, CLEAR, SET_CONTEXT_MAP, OTHER }
}
