package com.example.e2e.coupon;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CouponReloadJob {
    private final CouponLookup lookup;

    public CouponReloadJob(CouponLookup lookup) {
        this.lookup = lookup;
    }

    @Scheduled(fixedDelay = 600000)
    public void run() {
        log.info("Coupon reload started"); // logast:wording
        lookup.reload();
        log.info("Coupon reload completed"); // logast:wording
    }
}
