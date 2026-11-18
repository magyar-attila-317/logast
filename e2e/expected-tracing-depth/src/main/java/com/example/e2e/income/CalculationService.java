package com.example.e2e.income;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** processRow holds the sales row for the whole flow: the highest stable point for its couponId. */
@Service
public class CalculationService {
    private static final Logger log = LoggerFactory.getLogger(CalculationService.class);

    public void processAll(List<SalesTotals> rows, List<CouponDetails> allCoupons) {
        for (SalesTotals row : rows) {
            processRow(row, allCoupons);
        }
    }

    private IncomeOutcome processRow(SalesTotals salesData, List<CouponDetails> allCouponDetails) {
        log.trace("Processing coupon [couponId={}]", salesData.couponId());
        List<CouponDetails> matchingCoupons = filterMatchingCoupons(allCouponDetails, salesData.couponId());
        return IncomeCalculator.calculate(salesData, matchingCoupons);
    }

    private List<CouponDetails> filterMatchingCoupons(List<CouponDetails> allCoupons, String couponId) {
        return allCoupons.stream().filter(coupon -> coupon.couponId().equals(couponId)).toList();
    }
}
