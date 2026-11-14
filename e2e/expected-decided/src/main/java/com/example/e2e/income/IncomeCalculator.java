package com.example.e2e.income;

import com.example.e2e.logging.MdcScope;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
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
            Optional<String> exclusion = validateCoupon(salesData, coupon);
            if (exclusion.isEmpty()) {
                return calculateIncome(salesData, coupon);
            }
            log.trace("Coupon excluded [reason={}]", exclusion.get());
        }
        return new IncomeOutcome(true, BigDecimal.ZERO);
    }

    private static Optional<String> validateCoupon(SalesTotals salesData, CouponDetails coupon) {
        try (var mdc = MdcScope.of("agreementId", coupon.agreementId())) {
            for (CouponCheck check : CouponChecks.all()) {
                Optional<String> result = check.validate(coupon, salesData);
                if (result.isPresent()) {
                    return result;
                }
            }
            return Optional.empty();
        }
    }

    private static IncomeOutcome calculateIncome(SalesTotals salesData, CouponDetails coupon) {
        BigDecimal result = salesData.totalDiscount().multiply(coupon.fundingPercentage());
        log.debug("Income calculated [saleDay={}, result={}]", salesData.saleDay(), result);
        return new IncomeOutcome(false, result);
    }
}
