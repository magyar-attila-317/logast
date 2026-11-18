package com.example.e2e.edge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A default method that logs: the template sits in the interface. */
public interface Audited {
    Logger AUDIT_LOG = LoggerFactory.getLogger(Audited.class);

    default void audit(String couponId) {
        AUDIT_LOG.info("Coupon audited [couponId={}]", couponId);
    }
}
