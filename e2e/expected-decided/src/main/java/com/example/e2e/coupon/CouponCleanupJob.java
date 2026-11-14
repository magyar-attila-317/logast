package com.example.e2e.coupon;

import com.example.e2e.logging.AfterCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** A nightly job looping over a query result. */
@Component
public class CouponCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(CouponCleanupJob.class);
    private final CouponRepository repository;

    public CouponCleanupJob(CouponRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void cleanup() {
        log.info("Coupon sweep started");
        var couponIds = repository.findOpen();
        for (String couponId : couponIds) {
            log.debug("Closing stale coupon [couponId={}]", couponId);
            repository.close(couponId);
        }
        AfterCommit.log(() -> log.info("Coupon sweep completed [couponCount={}]", couponIds.size()));
    }
}
