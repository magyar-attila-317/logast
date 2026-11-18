package com.example.e2e.relay;

import com.example.e2e.logging.ObservedContext;
import org.springframework.stereotype.Component;

/** A plain bean Redis calls through MessageListenerAdapters. */
@Component
public class CouponRelay {
    private final RelayLog relayLog;

    CouponRelay(RelayLog relayLog) {
        this.relayLog = relayLog;
    }

    public void onCoupon(String couponId) {
        ObservedContext.entrypoint(RelayMetrics.COUPON_RELAY_ON_COUPON)
                .mdc("couponId", couponId)
                .debugOnSuccess()
                .run(() -> {
                    relayLog.record(couponId);
                });
    }

    public void onRefund(String refundId, String channel) {
        ObservedContext.entrypoint(RelayMetrics.COUPON_RELAY_ON_REFUND)
                .debugOnSuccess()
                .run(() -> {
                    relayLog.record(refundId);
                });
    }

    public void handleMessage(String voucherId) {
        ObservedContext.entrypoint(RelayMetrics.COUPON_RELAY_HANDLE_MESSAGE)
                .debugOnSuccess()
                .run(() -> {
                    relayLog.record(voucherId);
                });
    }
}
