package com.example.e2e.pledge;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PledgeReloadJob {
    private final PledgeLookup lookup;

    public PledgeReloadJob(PledgeLookup lookup) {
        this.lookup = lookup;
    }

    @Scheduled(fixedDelay = 600000)
    public void run() {
        lookup.reload();
    }
}
