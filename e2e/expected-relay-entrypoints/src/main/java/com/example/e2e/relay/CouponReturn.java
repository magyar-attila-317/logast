package com.example.e2e.relay;

/** A message payload: its couponId accessor is the MDC value. */
public record CouponReturn(String couponId, int quantity) {
}
