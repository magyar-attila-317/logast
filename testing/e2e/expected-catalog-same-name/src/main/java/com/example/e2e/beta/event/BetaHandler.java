package com.example.e2e.beta.event;

import com.example.e2e.logging.ObservedContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class BetaHandler {
    private static final Logger log = LoggerFactory.getLogger(BetaHandler.class);

    public void run1() {
        ObservedContext.entrypoint(com.example.e2e.alpha.event.EventMetrics.REFUND_SYNC)
                .quiet()
                .run(() -> {
                    log.info("Refund sync started");
                    work();
                    log.info("Refund sync completed");
                });
    }

    public void run2() {
        ObservedContext.entrypoint(EventMetrics.LEDGER_IMPORT)
                .quiet()
                .run(() -> {
                    log.info("Ledger import started");
                    work();
                    log.info("Ledger import completed");
                });
    }

    private void work() {
    }
}
