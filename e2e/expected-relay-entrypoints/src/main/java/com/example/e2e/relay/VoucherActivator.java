package com.example.e2e.relay;

import com.example.e2e.logging.ObservedContext;
import org.springframework.context.annotation.Bean;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.messaging.MessageHandler;
import org.springframework.stereotype.Component;

@Component
public class VoucherActivator {
    private final RelayLog relayLog;

    VoucherActivator(RelayLog relayLog) {
        this.relayLog = relayLog;
    }

    @ServiceActivator(inputChannel = "voucherRequests")
    public void issue(String voucherId) {
        ObservedContext.entrypoint(RelayMetrics.VOUCHER_ACTIVATOR_ISSUE)
                .debugOnSuccess()
                .run(() -> {
                    relayLog.record(voucherId);
                });
    }

    /** Defines a handler bean: the method is configuration, not a handler. */
    @Bean
    @ServiceActivator(inputChannel = "voucherAudits")
    public MessageHandler voucherAuditHandler() {
        return message -> relayLog.record("audit");
    }
}
