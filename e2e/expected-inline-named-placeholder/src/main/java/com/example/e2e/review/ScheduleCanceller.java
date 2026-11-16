package com.example.e2e.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ScheduleCanceller {
    private static final Logger log = LoggerFactory.getLogger(ScheduleCanceller.class);

    void cancel(String dealId) {
        log.info("No schedules found for given dealId: {}. There was nothhing to cancel.", dealId); // logast: LOG_FORMAT FMT-286bdb
    }
}
