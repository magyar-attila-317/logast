package com.example.e2e.loggers;

import java.util.logging.Logger;

class LegacyJob {
    private static final Logger log = Logger.getLogger(LegacyJob.class.getName());

    void run(Runnable step) {
        try {
            step.run();
        } catch (IllegalStateException e) {
        }
    }
}
