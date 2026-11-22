package com.example.e2e.timestamp;

import com.example.e2e.logging.ObservedContext;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class RefundJob {
    private static final Logger log = LoggerFactory.getLogger(RefundJob.class);

    void run(OffsetDateTime createdAt) {
        ObservedContext.entrypoint(TimestampMetrics.REFUND)
                .quiet()
                .run(() -> {
                    log.info("Refund started");
                    log.info("Refund completed [createdAt={}]", createdAt);
                });
    }
}
