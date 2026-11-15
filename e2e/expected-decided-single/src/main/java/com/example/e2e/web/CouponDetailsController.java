package com.example.e2e.web;

import com.example.e2e.coupon.CouponId;
import com.example.e2e.coupon.CouponLookup;
import com.example.e2e.logging.MdcScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class CouponDetailsController {
    private final CouponLookup lookup;

    public CouponDetailsController(CouponLookup lookup) {
        this.lookup = lookup;
    }

    @GetMapping("/coupons/{couponId}/details")
    public String details(@PathVariable String couponId) {
        try (var mdc = MdcScope.of("couponId", couponId)) {
            return lookup.find(couponId);
        }
    }

    @PostMapping("/coupons/{couponId}/hold")
    public void hold(@PathVariable CouponId couponId) {
        try (var mdc = MdcScope.of("couponId", couponId.value())) {
            log.info("Coupon sweep started");
            lookup.find(couponId.value());
        }
    }
}
