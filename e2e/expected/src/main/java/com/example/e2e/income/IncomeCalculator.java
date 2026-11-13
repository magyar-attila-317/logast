package com.example.e2e.income;

import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Static calculator called per row; the matched coupon (same id, other source) is evaluated in a loop. */
public final class IncomeCalculator {
    private static final Logger log = LoggerFactory.getLogger(IncomeCalculator.class);

    private IncomeCalculator() {
    }

    public static IncomeOutcome calculate(SalesTotals salesData, List<CouponDetails> matchingCoupons) {
        log.debug("Calculating income [saleDay={}, matchingCoupons={}]", salesData.saleDay(), matchingCoupons.size());
        if (matchingCoupons.isEmpty()) {
            log.debug("Coupon not found in the coupon list");
            return new IncomeOutcome(true, BigDecimal.ZERO);
        }
        return evaluateOutcome(salesData, matchingCoupons);
    }

    private static IncomeOutcome evaluateOutcome(SalesTotals salesData, List<CouponDetails> sortedCoupons) {
        for (CouponDetails coupon : sortedCoupons) {
            if (coupon.fundingPercentage() != null) {
                return calculateIncome(salesData, coupon);
            }
            log.trace("Coupon excluded");
        }
        return new IncomeOutcome(true, BigDecimal.ZERO);
    }

    private static IncomeOutcome calculateIncome(SalesTotals salesData, CouponDetails coupon) {
        BigDecimal result = salesData.totalDiscount().multiply(coupon.fundingPercentage());
        log.debug("Income calculated [saleDay={}, result={}]", salesData.saleDay(), result);
        return new IncomeOutcome(false, result);
    }
}
