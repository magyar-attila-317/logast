package com.example.e2e.job;

import org.slf4j.MDC;

/** A job base that puts its own context key (outside the vocabulary) into MDC around the abstract step. */
public abstract class BaseRunJob extends TpiJob {
    @Override
    protected void doExecute() {
        String runCode = resolveRunCode();
        try (var mdc = MDC.putCloseable("runCode", runCode)) {
            doRun(runCode);
        }
    }

    protected abstract void doRun(String runCode);

    private String resolveRunCode() {
        return "R1";
    }
}
