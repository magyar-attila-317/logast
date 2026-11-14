package com.example.e2e.job;

import org.platformlib.Week;
import org.slf4j.MDC;

/** A job base that puts its own context key (outside the vocabulary) into MDC around the abstract step. */
public abstract class BaseRunJob extends TpiJob {
    @Override
    protected void doExecute() {
        String runCode = resolveRunCode();
        Week week = Week.current();
        try (var mdc = MDC.putCloseable("runCode", runCode)) {
            doRun(runCode, week);   // a value of a library type logast cannot see: the call must still resolve
        }
    }

    protected abstract void doRun(String runCode, Week week);

    private String resolveRunCode() {
        return "R1";
    }
}
