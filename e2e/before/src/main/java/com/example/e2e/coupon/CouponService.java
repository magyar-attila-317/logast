package com.example.e2e.coupon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** couponId passed down a chain, logged in free-form messages on every level. */
@Service
public class CouponService {
    private static final Logger log = LoggerFactory.getLogger(CouponService.class);
    private final CouponSender sender;
    private final CouponRepository repository;

    public CouponService(CouponSender sender, CouponRepository repository) {
        this.sender = sender;
        this.repository = repository;
    }

    public void publish(String couponId) {
        log.info("Publishing coupon {}", couponId);
        sender.send(couponId);
        repository.close(couponId);
        log.info("Coupon {} published", couponId);
    }
}
