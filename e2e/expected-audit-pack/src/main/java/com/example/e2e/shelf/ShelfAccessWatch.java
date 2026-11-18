package com.example.e2e.shelf;

import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.stereotype.Component;

/** Counts denied access, but nothing publishes authorization events. */
@Slf4j
@Component
public class ShelfAccessWatch {
    private final AtomicLong denied = new AtomicLong();

    @EventListener
    public void onDenied(AuthorizationDeniedEvent event) {
        denied.incrementAndGet();
        log.info("Shelf access watch completed"); // logast:wording
        ShelfMetrics.shelfAccessWatchCompleted(); // logast:metric review, confidence: low
    }
}
