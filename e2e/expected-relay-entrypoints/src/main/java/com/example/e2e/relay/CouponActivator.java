package com.example.e2e.relay;

import com.example.e2e.logging.ObservedContext;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.stereotype.Component;

/** A Spring Integration endpoint: each message on the input channel calls the method. */
@Component
public class CouponActivator {
    private final RelayLog relayLog;

    CouponActivator(RelayLog relayLog) {
        this.relayLog = relayLog;
    }

    @ServiceActivator(inputChannel = "couponRequests")
    public void redeem(String couponId) {
        ObservedContext.entrypoint(RelayMetrics.COUPON_ACTIVATOR_REDEEM)
                .mdc("couponId", couponId)
                .debugOnSuccess()
                .run(() -> {
                    relayLog.record(couponId);
                });
    }

    @ServiceActivator(inputChannel = "couponReturns")
    public void giveBack(CouponReturn command) {
        ObservedContext.entrypoint(RelayMetrics.COUPON_ACTIVATOR_GIVE_BACK)
                .mdc("couponId", command.couponId())
                .debugOnSuccess()
                .run(() -> {
                    relayLog.record(command.couponId() + " x" + command.quantity());
                });
    }
}
