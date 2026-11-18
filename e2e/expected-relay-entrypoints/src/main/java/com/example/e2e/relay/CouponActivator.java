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
}
