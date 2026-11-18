package com.example.e2e.coupon;

/** A typed id: in MDC it must be its value, not CouponId[value=...] (#49). */
public record CouponId(String value) {
}
