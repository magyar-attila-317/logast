package com.example.e2e.intake;

import org.springframework.stereotype.Component;

@Component
public class CouponFeed {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CouponFeed.class);

    void load() {
        log.info("Coupon feed loaded");
    }
}
