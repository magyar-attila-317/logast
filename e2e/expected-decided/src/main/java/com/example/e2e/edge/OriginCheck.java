package com.example.e2e.edge;

import com.example.e2e.income.CouponDetails;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OriginCheck implements Check<CouponDetails> {
    @Override
    public Optional<String> test(CouponDetails coupon) {
        log.debug("Origin checked [origin={}]", coupon.origin());
        return Optional.empty();
    }
}
