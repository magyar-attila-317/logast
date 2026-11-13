package com.example.e2e.edge;

import com.example.e2e.income.CouponCheck;
import com.example.e2e.income.CouponDetails;
import com.example.e2e.income.SalesTotals;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Holds the row (and its couponId) for the whole flow; every call below is one edge case. */
@Slf4j
@Service
public class EdgeFlow implements Audited {
    public void process(SalesTotals salesData) {
        log.info("Edge processing started [couponId={}]", salesData.couponId());
        CouponRules.check(salesData.couponId());                                   // @UtilityClass, methods not declared static
        List.of(salesData).forEach(this::publishOne);                              // method reference
        for (Exclusion exclusion : Exclusion.values()) {                           // enum constants with bodies
            exclusion.test(salesData);
        }
        CouponDetails coupon = new CouponDetails(salesData.couponId(), null, null, null);
        CouponCheck inline = (c, s) -> {                                           // lambda implementing an interface
            log.debug("Inline check [couponId={}]", c.couponId());
            return Optional.empty();
        };
        inline.validate(coupon, salesData);
        new CouponCheck() {                                                        // anonymous implementation
            @Override
            public Optional<String> validate(CouponDetails c, SalesTotals s) {
                log.debug("Anonymous check [couponId={}]", c.couponId());
                return Optional.empty();
            }
        }.validate(coupon, salesData);
        Check<CouponDetails> origin = new OriginCheck();                           // generic interface
        origin.test(coupon);
        audit(salesData.couponId());                                               // default method of an interface
        EdgeLogHelper.info("Coupon processed [couponId={}]", salesData.couponId()); // a home-made log helper
        CompletableFuture.runAsync(() ->                                           // another thread: MDC does not follow
                log.info("Coupon archived [couponId={}]", salesData.couponId()));
        log.info("Coupon parsed [couponId={}]", UUID.nameUUIDFromBytes(salesData.couponId().getBytes())); // a transformed value
        new CouponTask(salesData.couponId()).run();                                // an id kept in an instance field
        log.info("Edge processing completed [couponId={}]", salesData.couponId());
    }

    private void publishOne(SalesTotals row) {
        log.debug("Publishing coupon [couponId={}]", row.couponId());
    }
}
