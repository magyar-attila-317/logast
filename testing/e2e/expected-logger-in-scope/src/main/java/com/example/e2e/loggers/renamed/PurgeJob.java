package com.example.e2e.loggers.renamed;

import com.example.e2e.loggers.LoggersMetrics;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class PurgeJob {
    void run(Runnable step) {
        LOG.debug("Purge started");
        try {
            step.run();
        } catch (IllegalStateException e) {
            LOG.warn("Step failed", e);
            LoggersMetrics.stepFailed(); // logast:metric review, confidence: medium
        }
    }
}
