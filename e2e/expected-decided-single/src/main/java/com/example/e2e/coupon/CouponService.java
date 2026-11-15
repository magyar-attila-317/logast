package com.example.e2e.coupon;

import com.example.e2e.audit.AuditLog;
import com.example.e2e.logging.MdcScope;
import com.example.e2e.metrics.BusinessMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** couponId passed down a chain, logged in free-form messages on every level. */
@Service
public class CouponService {
    private static final Logger log = LoggerFactory.getLogger(CouponService.class);
    private final CouponSender sender;
    private final CouponRepository repository;
    private final AuditLog audit;

    public CouponService(CouponSender sender, CouponRepository repository, AuditLog audit) {
        this.sender = sender;
        this.repository = repository;
        this.audit = audit;
    }

    /** Audited only when the delete went through: a failed or rejected attempt leaves no record. */
    public void delete(String couponId) {
        repository.close(couponId);
        audit.record("coupon.delete", couponId, "done");
    }

    /** Audited either way: the failed attempt is recorded too. */
    public void revoke(String couponId) {
        try {
            repository.close(couponId);
            audit.record("coupon.revoke", couponId, "done");
        } catch (RuntimeException e) {
            audit.record("coupon.revoke", couponId, "failed");
            throw e;
        }
    }

    public void publish(String couponId) {
        log.info("Publishing coupon");
        sender.send(couponId);
        repository.close(couponId);
        log.info("Coupon published");
        BusinessMetrics.couponPublished();
    }

    public void redeem(RedeemRequest request) {
        try (var mdc = MdcScope.of("couponId", request.code())) {
            log.info("Coupon redemption started");
            sender.send(request.code());
            log.info("Coupon redemption completed [tableId={}]", request.tableId());
            BusinessMetrics.couponRedemptionCompleted();
        }
    }
}
