package com.example.e2e.relay;

import com.example.e2e.logging.ObservedContext;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.stereotype.Component;

/** A Spring Integration endpoint: each message on the input channel calls the method. */
@Component
public class PledgeActivator {
    private final RelayLog relayLog;
    private final com.example.e2e.audit.AuditLog auditLog;

    PledgeActivator(RelayLog relayLog, com.example.e2e.audit.AuditLog auditLog) {
        this.relayLog = relayLog;
        this.auditLog = auditLog;
    }

    @ServiceActivator(inputChannel = "pledgeRequests")
    public void allot(String pledgeId) {
        ObservedContext.entrypoint(RelayMetrics.PLEDGE_ACTIVATOR_ALLOT)
                .mdc("pledgeId", pledgeId)
                .debugOnSuccess()
                .run(() -> {
                    relayLog.record(pledgeId);
                    auditLog.record("pledge-allotted", pledgeId, "ok");
                });
    }

    @ServiceActivator(inputChannel = "pledgeReturns")
    public void giveBack(PledgeReturn command) {
        ObservedContext.entrypoint(RelayMetrics.PLEDGE_ACTIVATOR_GIVE_BACK)
                .mdc("pledgeId", command.pledgeId())
                .debugOnSuccess()
                .run(() -> {
                    relayLog.record(command.pledgeId() + " x" + command.quantity());
                });
    }
}
