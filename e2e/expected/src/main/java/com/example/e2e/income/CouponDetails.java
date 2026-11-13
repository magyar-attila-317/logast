package com.example.e2e.income;

import java.math.BigDecimal;

public record CouponDetails(String couponId, String origin, BigDecimal fundingPercentage) {
}
