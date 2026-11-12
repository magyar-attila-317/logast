package dev.logast.model;

import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

import java.util.List;

/**
 * A call (or method reference) from a source method.
 *
 * @param async             passed to an executor / CompletableFuture, so it runs on another thread
 * @param asyncManaged      that executor is Spring-managed (a TaskDecorator bean applies)
 * @param enclosingCatches  catch clauses guarding the call within the calling method
 */
public record CallSite(J tree, JavaType.Method type, int line, boolean async, boolean asyncManaged,
                       List<CatchInfo> enclosingCatches) {
}
