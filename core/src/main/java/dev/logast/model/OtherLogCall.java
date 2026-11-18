package dev.logast.model;

import org.openrewrite.java.tree.J;

/**
 * A log call through another logging API (Log4j2, java.util.logging, Commons Logging, …): logast checks and edits only
 * SLF4J lines (#53), so it accounts for this one as skipped, with the API it uses (#72).
 */
public record OtherLogCall(J.MethodInvocation call, String api) {
}
