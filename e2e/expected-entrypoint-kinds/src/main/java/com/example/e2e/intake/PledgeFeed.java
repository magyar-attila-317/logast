package com.example.e2e.intake;

import org.springframework.stereotype.Component;

@Component
public class PledgeFeed {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PledgeFeed.class);

    void load() {
        log.info("Pledge feed loaded");
    }
}
