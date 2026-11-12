package dev.logast.model;

import org.openrewrite.java.tree.J;

/**
 * Work handed to another thread.
 *
 * @param call          the method invocation or {@code new Thread(..)} that hands the work over
 * @param springManaged receiver is a Spring TaskExecutor (decorated by a TaskDecorator bean)
 */
public record AsyncHop(J call, String api, boolean springManaged, int line) {
}
