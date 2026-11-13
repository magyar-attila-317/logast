package com.example.e2e.edge;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

/** Lombok makes these methods static at compile time: the source declares them as instance methods. */
@Slf4j
@UtilityClass
public class CouponRules {
    public void check(String couponId) {
        log.debug("Checking rules [couponId={}]", couponId);
        if (couponId.isBlank()) {
            log.debug("No rules apply [couponId={}]", couponId);
        }
    }
}
