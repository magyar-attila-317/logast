package com.example.e2e.coupon;

public class CouponMissing extends RuntimeException {
    public CouponMissing(String couponId, Throwable cause) {
        super("coupon not found: " + couponId, cause);
    }
}
