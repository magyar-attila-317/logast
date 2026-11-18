package com.example.e2e.edge;

import lombok.extern.slf4j.Slf4j;

/** The id lives in a field for the object's lifetime. */
@Slf4j
public class CouponTask {
    private final String couponId;

    public CouponTask(String couponId) {
        this.couponId = couponId;
    }

    public void run() {
        log.info("Task step one");
        log.info("Task step two");
    }
}
