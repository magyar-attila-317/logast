package com.example.e2e.coupon;

/** The coupon id arrives inside a request object, not as a parameter named couponId. */
public record RedeemRequest(String code, String tableId) {
}
