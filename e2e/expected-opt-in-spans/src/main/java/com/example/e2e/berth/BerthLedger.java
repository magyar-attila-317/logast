package com.example.e2e.berth;

import com.example.e2e.logging.ObservedContext;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class BerthLedger {
    private static final Logger log = LoggerFactory.getLogger(BerthLedger.class);

    @EventListener
    @Observed(name = "berth.ledger-entry")
    public void onReleased(BerthReleased event) {
        log.info("Ledger sync started");
        log.info("Ledger sync completed");
    }

    @EventListener
    @Observed(name = "berth.inspection")
    public void onInspected(BerthInspected event) {
        log.info("Inspection sync started");
        log.info("Inspection sync completed");
    }

    @EventListener
    public void onInspectedNotify(BerthInspected event) {
        ObservedContext.entrypoint(BerthMetrics.NOTICE_SYNC)
                .quiet()
                .run(() -> {
                    log.info("Notice sync started");
                    log.info("Notice sync completed");
                });
    }
}
