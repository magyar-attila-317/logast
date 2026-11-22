package com.example.e2e.loggers;

import lombok.extern.log4j.Log4j2;

@Log4j2
class ArchiveJob {
    void run(Runnable step) {
        try {
            step.run();
        } catch (IllegalStateException e) {
            log.warn("Archive step failed", e);
        }
    }

    void retry(Runnable step) {
        try {
            step.run();
        } catch (IllegalArgumentException e) {
        }
    }
}
