package com.example.e2e.loggers;

class CleanupJob extends BaseJob {
    @Override
    void run(Runnable step) {
        try {
            step.run();
        } catch (IllegalStateException e) {
            log.warn("Step failed", e);
            LoggersMetrics.cleanupStepFailed();
        }
    }
}
