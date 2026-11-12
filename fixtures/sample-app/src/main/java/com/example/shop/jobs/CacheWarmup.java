package com.example.shop.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Startup work: Spring publishes ApplicationReadyEvent outside any trace, so these logs have no trace id. */
@Component
public class CacheWarmup {
    private static final Logger log = LoggerFactory.getLogger(CacheWarmup.class);

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() { // expect: ENTRYPOINT_NOT_OBSERVED
        log.info("Cache warm-up started");
        load();
        log.info("Cache warm-up completed");
    }

    @EventListener
    public void onReady(ApplicationReadyEvent event) { // expect: ENTRYPOINT_NOT_OBSERVED, JOB_OUTCOME_MISSING
        log.info("Price list refresh started");
    }

    private void load() {
    }
}
