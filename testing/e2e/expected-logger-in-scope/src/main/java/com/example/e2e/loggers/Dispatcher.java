package com.example.e2e.loggers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class Dispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(Dispatcher.class);

    class Worker {
        void run(Runnable step) {
            try {
                step.run();
            } catch (IllegalStateException e) {
                LOGGER.warn("Step failed", e);
                LoggersMetrics.workerStepFailed();
            }
        }
    }
}
