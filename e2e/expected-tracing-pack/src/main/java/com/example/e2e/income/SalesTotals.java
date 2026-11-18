package com.example.e2e.income;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One coupon's sales for a day: the coupon id travels inside this record, never as a parameter of its own. */
public record SalesTotals(String couponId, LocalDate saleDay, BigDecimal totalDiscount) {
}
