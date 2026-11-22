package com.example.e2e.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ScheduleCanceller {
    private static final Logger log = LoggerFactory.getLogger(ScheduleCanceller.class);

    void cancel(String orderId) {
        log.info("No schedules found for given orderId: {}. There was nothing to cancel.", orderId);
    }
}
