package com.example.e2e.job;

import com.example.e2e.logging.MdcScope;
import org.orchardlib.Week;

/** A job base that puts its own context key (outside the vocabulary) into MDC around the abstract step. */
public abstract class BaseRunJob extends SettlementJob {
    @Override
    protected void doExecute() {
        String runCode = resolveRunCode();
        Week week = Week.current();
        try (var mdc = MdcScope.of("runCode", runCode)) {
            doRun(runCode, week);   // a value of a library type logast cannot see: the call must still resolve
        }
    }

    protected abstract void doRun(String runCode, Week week);

    private String resolveRunCode() {
        return "R1";
    }
}
