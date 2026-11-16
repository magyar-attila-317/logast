package com.example.e2e.edge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A default method that logs: the template sits in the interface. */
public interface Audited {
    Logger AUDIT_LOG = LoggerFactory.getLogger("audit");

    default void audit(String couponId) {
        AUDIT_LOG.info("Coupon audited"); // logast: METRIC_OUTCOME_UNMEASURED OUM-49d3bd
    }
}
