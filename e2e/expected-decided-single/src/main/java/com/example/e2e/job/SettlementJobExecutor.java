package com.example.e2e.job;

import com.example.e2e.logging.ObservedContext;
import com.example.e2e.metrics.BusinessMetrics;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class SettlementJobExecutor {
    private final SettlementJob job;

    public SettlementJobExecutor(SettlementJob job) {
        this.job = job;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        ObservedContext.entrypoint(BusinessMetrics.SETTLEMENT_JOB_EXECUTOR_ON_READY)
                .failureLoggedByCaller()
                .run(() -> {
                    job.execute();
                });
    }
}
