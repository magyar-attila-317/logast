package com.example.shop.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Startup work: runs once per boot, so no observation of its own (#272), though Spring publishes the event outside any trace. */
@Component
public class CacheWarmup {
    private static final Logger log = LoggerFactory.getLogger(CacheWarmup.class);

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        log.info("Cache warm-up started");
        load();
        log.info("Cache warm-up completed"); // expect: METRIC_OUTCOME_UNMEASURED
    }

    @EventListener
    public void onReady(ApplicationReadyEvent event) { // expect-not: JOB_OUTCOME_MISSING (a startup check that starts no work)
        log.info("Price list refresh started");
    }

    private void load() {
    }
}
