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
                    boolean inResource, org.openrewrite.java.tree.Expression keyExpr) {
    public MdcOp(Kind kind, String key, J.MethodInvocation call, J.Block block, boolean inFinally, boolean inResource) {
        this(kind, key, call, block, inFinally, inResource,
                call.getArguments().isEmpty() ? null : call.getArguments().getFirst());
    }

    /** Same op with its key resolved (a String constant). */
    public MdcOp withKey(String resolved) {
        return new MdcOp(kind, resolved, call, block, inFinally, inResource, keyExpr);
    }

    public enum Kind { PUT, PUT_CLOSEABLE, REMOVE, CLEAR, SET_CONTEXT_MAP, OTHER }
}
