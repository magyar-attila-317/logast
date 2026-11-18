package com.example.e2e.coupon;

import com.example.e2e.logging.MdcScope;
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

    /** A failure logged and handled here: an outcome worth counting by default. */
    public boolean resend(String couponId) {
        try (var mdc = MdcScope.of("coupon.id", couponId)) {
            try {
                send(couponId);
                return true;
            } catch (IllegalStateException e) {
                log.warn("Coupon resend failed", e);
                CouponMetrics.couponResendFailed(); // logast:metric review, confidence: medium
                return false;
            }
        }
    }
}
