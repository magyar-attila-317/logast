package com.example.e2e.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Returns a Mono: the body only assembles the pipeline, a scope around it would end before the lookup runs. */
@Service
public class ReactiveCouponLookup {
    private static final Logger log = LoggerFactory.getLogger(ReactiveCouponLookup.class);

    public Mono<String> find(String couponId) {
        log.info("Coupon lookup started [coupon.id={}]", couponId);
        return Mono.just(couponId)
                .doOnNext(found -> log.debug("Coupon lookup matched [coupon.id={}]", couponId))
                .doOnError(e -> log.warn("Coupon lookup failed [coupon.id={}]", couponId, e));
    }
}
