package com.example.e2e.job;

import com.example.e2e.logging.ObservedContext;
import com.example.e2e.metrics.BusinessMetrics;
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
        ObservedContext.entrypoint(BusinessMetrics.PUBLISHING)
                .quiet()
                .run(() -> {
                    log.info("Publishing started");
                    var summary = RunSummary.of(runCode, week);
                    report(summary);   // overloaded: the var's type picks report(RunSummary)
                    log.info("Publishing completed");
                });
    }

    private void report(RunSummary summary) {
        log.debug("Run summary [items={}]", summary.items());
    }

    private void report(String line) {
        log.debug("Run note [line={}]", line);
    }
}
