package com.example.e2e.job;

import com.example.e2e.logging.ObservedContext;
import org.orchardlib.Week;
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
        ObservedContext.entrypoint(JobMetrics.PUBLISHING)
                .quiet()
                .run(() -> {
                    log.info("Publishing started [runCode={}]", runCode);
                    var summary = RunSummary.of(runCode, week);
                    report(summary);   // overloaded: the var's type picks report(RunSummary)
                    String current = runCode;                                       // the same value under another name
                    log.debug("Publishing batch [runCode={}]", current);
                    notifyDone(runCode);
                    log.info("Publishing completed [runCode={}]", runCode);
                });
    }

    /** Every caller passes the run code: the parameter is the value MDC already carries. */
    private void notifyDone(String code) {
        log.debug("Publishing notified [runCode={}]", code);
    }

    private void report(RunSummary summary) {
        log.debug("Run summary [runCode={}, items={}]", summary.runCode(), summary.items());
    }

    private void report(String line) {
        log.debug("Run note [line={}]", line);
    }
}
