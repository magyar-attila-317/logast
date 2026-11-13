package com.example.e2e.job;

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
        job.execute();
    }
}
