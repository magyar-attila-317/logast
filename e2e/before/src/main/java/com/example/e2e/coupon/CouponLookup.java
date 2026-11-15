package com.example.e2e.coupon;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Log-and-rethrow on two paths (#46): find's exception ends in an @ExceptionHandler that maps it without logging, so
 * its log line is the only record and must stay; reload only runs from a @Scheduled job, which Spring logs.
 */
@Service
public class CouponLookup {
    private static final Logger log = LoggerFactory.getLogger(CouponLookup.class);
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String find(String couponId) {
        try {
            return load(couponId);
        } catch (IllegalStateException e) {
            log.warn("Coupon lookup failed", e);
            throw new CouponMissing(couponId, e);
        }
    }

    public void reload() {
        try {
            cache.clear();
            load("all");
        } catch (IllegalStateException e) {
            log.error("Coupon reload failed", e);
            throw e;
        }
    }

    private String load(String couponId) {
        String value = cache.get(couponId);
        if (value == null) {
            throw new IllegalStateException("not cached: " + couponId);
        }
        return value;
    }
}
