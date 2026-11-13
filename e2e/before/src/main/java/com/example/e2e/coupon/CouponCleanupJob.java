package com.example.e2e.coupon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** A nightly job looping over a query result. */
@Component
public class CouponCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(CouponCleanupJob.class);
    private final CouponRepository repository;

    public CouponCleanupJob(CouponRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "0 0 2 * * *")
    public void cleanup() {
        for (String couponId : repository.findOpen()) {
            log.info("Closing stale coupon {}", couponId);
            repository.close(couponId);
        }
    }
}
