package com.example.e2e.job;

import org.platformlib.Week;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** The step logs the base class's MDC key again. */
@Component
@Profile("publish")
public class PublishRunJob extends BaseRunJob {
    private static final Logger log = LoggerFactory.getLogger(PublishRunJob.class);

    @Override
    protected void doRun(String runCode, Week week) {
        log.info("Publishing started [runCode={}]", runCode);
        var summary = RunSummary.of(runCode, week);
        report(summary);   // overloaded: the var's type picks report(RunSummary)
        log.info("Publishing completed [runCode={}]", runCode);
    }

    private void report(RunSummary summary) {
        log.debug("Run summary [runCode={}, items={}]", summary.runCode(), summary.items());
    }

    private void report(String line) {
        log.debug("Run note [line={}]", line);
    }
}
