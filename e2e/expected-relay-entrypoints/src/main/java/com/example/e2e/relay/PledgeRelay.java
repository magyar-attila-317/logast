package com.example.e2e.relay;

import com.example.e2e.logging.ObservedContext;
import org.springframework.stereotype.Component;

/** A plain bean Redis calls through MessageListenerAdapters. */
@Component
public class PledgeRelay {
    private final RelayLog relayLog;

    PledgeRelay(RelayLog relayLog) {
        this.relayLog = relayLog;
    }

    public void onPledge(String pledgeId) {
        ObservedContext.entrypoint(RelayMetrics.PLEDGE_RELAY_ON_PLEDGE)
                .mdc("pledgeId", pledgeId)
                .debugOnSuccess()
                .run(() -> {
                    relayLog.record(pledgeId);
                });
    }

    public void onRefund(String refundId, String channel) {
        ObservedContext.entrypoint(RelayMetrics.PLEDGE_RELAY_ON_REFUND)
                .debugOnSuccess()
                .run(() -> {
                    relayLog.record(refundId);
                });
    }

    public void handleMessage(String voucherId) {
        ObservedContext.entrypoint(RelayMetrics.PLEDGE_RELAY_HANDLE_MESSAGE)
                .debugOnSuccess()
                .run(() -> {
                    relayLog.record(voucherId);
                });
    }
}
