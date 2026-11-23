package com.example.e2e.gamma;

import com.example.e2e.logging.ObservedContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GammaHandler {
    private static final Logger log = LoggerFactory.getLogger(GammaHandler.class);

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
        ObservedContext.entrypoint(com.example.e2e.beta.event.EventMetrics.LEDGER_IMPORT)
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
