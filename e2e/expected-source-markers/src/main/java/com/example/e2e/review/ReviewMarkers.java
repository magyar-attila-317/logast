package com.example.e2e.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ReviewMarkers {
    private static final Logger log = LoggerFactory.getLogger(ReviewMarkers.class);

    void run(String value) {
        log.warn("doing {} with something something", value); // logast: LOG_FORMAT FMT-6bc686
        log.info("Refund completed"); // logast: METRIC_OUTCOME_UNMEASURED OUM-ad1d56
    }
}
