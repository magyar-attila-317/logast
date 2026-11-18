package com.example.e2e.relay;

import com.example.e2e.logging.ObservedContext;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.stereotype.Component;

/** A Spring Integration endpoint: each message on the input channel calls the method. */
@Component
public class PledgeActivator {
    private final RelayLog relayLog;

    PledgeActivator(RelayLog relayLog) {
        this.relayLog = relayLog;
    }

    @ServiceActivator(inputChannel = "pledgeRequests")
    public void allot(String pledgeId) {
        ObservedContext.entrypoint(RelayMetrics.PLEDGE_ACTIVATOR_ALLOT)
                .mdc("pledgeId", pledgeId)
                .debugOnSuccess()
                .run(() -> {
                    relayLog.record(pledgeId);
                });
    }
}
