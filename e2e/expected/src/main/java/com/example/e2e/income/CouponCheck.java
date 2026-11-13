package com.example.e2e.income;

import java.util.Optional;

/** One check of a matched coupon; implementations are nested classes of CouponChecks. */
public interface CouponCheck {
    Optional<String> validate(CouponDetails coupon, SalesTotals salesData);
}
