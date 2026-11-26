package com.example.e2e.pledge;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reloads the pledges for the nightly audit. No logging by owner rule (#334: logast adds none here).
 */
@Component
public class PledgeAuditJob {
    private final PledgeLookup lookup;

    public PledgeAuditJob(PledgeLookup lookup) {
        this.lookup = lookup;
    }

    @Scheduled(cron = "0 0 4 * * *")
    public void audit() {
        lookup.reload();
    }
}
