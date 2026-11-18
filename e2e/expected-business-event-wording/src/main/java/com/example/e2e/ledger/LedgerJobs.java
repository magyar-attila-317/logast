package com.example.e2e.ledger;

import com.example.e2e.logging.ObservedContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LedgerJobs {
    private static final Logger log = LoggerFactory.getLogger(LedgerJobs.class);

    public void syncRefunds() {
        ObservedContext.entrypoint(LedgerMetrics.REFUND_SYNC)
                .quiet()
                .run(() -> {
                    log.info("Refund sync started");
                    work();
                    log.info("Refund sync completed");
                });
    }

    public void importLedger() {
        ObservedContext.entrypoint(LedgerMetrics.LEDGER_IMPORT)
                .quiet()
                .run(() -> {
                    log.info("Processing ledger import");
                    work();
                    log.info("Ledger import completed");
                });
    }

    public void touchOrders() {
        log.info("Processing order batch");
        work();
    }

    private void work() {
    }
}
