package com.example.e2e.pledge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PledgeReloadJob {
    private final PledgeLookup lookup;

    public PledgeReloadJob(PledgeLookup lookup) {
        this.lookup = lookup;
    }

    @Scheduled(fixedDelay = 600000)
    public void run() {
        log.info("Pledge sweep started");
        lookup.reload();
        log.info("Pledge sweep completed");
    }
}
