package com.example.e2e.income;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Checks as nested static classes (the shape real services use), run in order by the calculator. */
public final class CouponChecks {
    private static final Logger log = LoggerFactory.getLogger(CouponChecks.class);
    static final String CHECK_LOG_MESSAGE = "Validating coupon [check={}, couponId={}, agreementId={}, saleDay={}]";

    private CouponChecks() {
    }

    public static List<CouponCheck> all() {
        return List.of(new ApprovedAgreementCheck(), new FundingFoundCheck());
    }

    public static class ApprovedAgreementCheck implements CouponCheck {
        @Override
        public Optional<String> validate(CouponDetails coupon, SalesTotals salesData) {
            log.trace(CHECK_LOG_MESSAGE, "approved", coupon.couponId(), coupon.agreementId(), salesData.saleDay());
            if (coupon.agreementId() == null) {
                log.debug("Agreement missing [couponId={}, agreementId={}]", coupon.couponId(), coupon.agreementId());
                return Optional.of("NO_AGREEMENT");
            }
            return Optional.empty();
        }
    }

    public static class FundingFoundCheck implements CouponCheck {
        @Override
        public Optional<String> validate(CouponDetails coupon, SalesTotals salesData) {
            log.trace(CHECK_LOG_MESSAGE, "funding", coupon.couponId(), coupon.agreementId(), salesData.saleDay());
            if (coupon.fundingPercentage() == null) {
                log.debug("No funding found [couponId={}, agreementId={}]", coupon.couponId(), coupon.agreementId());
                return Optional.of("NO_FUNDING");
            }
            return Optional.empty();
        }
    }
}
