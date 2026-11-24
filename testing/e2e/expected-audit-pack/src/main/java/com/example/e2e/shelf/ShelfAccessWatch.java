package com.example.e2e.shelf;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.event.EventListener;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.stereotype.Component;

/** Counts denied access, but nothing publishes authorization events. */
@Component
public class ShelfAccessWatch {
    private final AtomicLong denied = new AtomicLong();

    @EventListener
    public void onDenied(AuthorizationDeniedEvent event) {
        denied.incrementAndGet();
    }
}
