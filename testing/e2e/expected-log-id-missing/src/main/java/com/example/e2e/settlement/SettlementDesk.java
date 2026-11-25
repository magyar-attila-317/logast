package com.example.e2e.settlement;

import com.example.e2e.logging.MdcScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SettlementDesk {
    private static final Logger log = LoggerFactory.getLogger(SettlementDesk.class);

    public void settle(String orderId, long amountCents) {
        try {
            charge(amountCents);
        } catch (IllegalStateException e) {
            log.warn("Order settlement failed [orderId={}]", orderId, e);
            SettlementMetrics.orderSettlementFailed();
        }
    }

    public void reconcile(String orderId, String pledgeId) {
        log.error("Pledge reconciliation failed");
        SettlementMetrics.pledgeReconciliationFailed();
    }

    public void close(String orderId) {
        try (var mdc = MdcScope.of("orderId", orderId)) {
            log.warn("Order closing failed");
            SettlementMetrics.orderClosingFailed();
        }
    }

    private void charge(long amountCents) {
        if (amountCents < 0) {
            throw new IllegalStateException("negative amount");
        }
    }
}
