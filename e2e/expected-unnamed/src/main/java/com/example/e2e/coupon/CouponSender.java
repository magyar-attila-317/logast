package com.example.e2e.coupon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CouponSender {
    private static final Logger log = LoggerFactory.getLogger(CouponSender.class);

    public void send(String couponId) {
        log.debug("Sending coupon to the channel");
        log.info("Coupon sent");
    }
}
