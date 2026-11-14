package com.example.e2e.job;

import com.example.e2e.logging.ObservedContext;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class TpiJobExecutor {
    private final TpiJob job;

    public TpiJobExecutor(TpiJob job) {
        this.job = job;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        ObservedContext.entrypoint("tpi-job-executor.on-ready")
                .failureLoggedByCaller()
                .run(() -> {
                    job.execute();
                });
    }
}
