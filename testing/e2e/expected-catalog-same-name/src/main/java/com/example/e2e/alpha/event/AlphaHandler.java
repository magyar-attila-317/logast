package com.example.e2e.alpha.event;

import com.example.e2e.logging.ObservedContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AlphaHandler {
    private static final Logger log = LoggerFactory.getLogger(AlphaHandler.class);

    public void run1() {
        ObservedContext.entrypoint(EventMetrics.REFUND_SYNC)
                .quiet()
                .run(() -> {
                    log.info("Refund sync started");
                    work();
                    log.info("Refund sync completed");
                });
    }

    private void work() {
    }
}
