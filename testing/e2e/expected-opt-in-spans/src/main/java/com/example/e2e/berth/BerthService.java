package com.example.e2e.berth;

import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
class BerthService {
    private static final Logger log = LoggerFactory.getLogger(BerthService.class);
    private final ApplicationEventPublisher events;

    BerthService(ApplicationEventPublisher events) {
        this.events = events;
    }

    @Observed(name = "berth.release")
    public void release(String berthCode) {
        log.info("Berth released");
        events.publishEvent(new BerthReleased(berthCode));
    }

    public void assign(String berthCode) {
        log.info("Berth assigned");
    }
}
