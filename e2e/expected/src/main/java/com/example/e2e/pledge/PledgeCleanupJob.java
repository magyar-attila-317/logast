package com.example.e2e.pledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** A nightly job looping over a query result. */
@Component
public class PledgeCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(PledgeCleanupJob.class);
    private final PledgeRepository repository;

    public PledgeCleanupJob(PledgeRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "0 0 2 * * *")
    public void cleanup() {
        for (String pledgeId : repository.findOpen()) {
            log.debug("Closing stale pledge [pledgeId={}]", pledgeId);
            repository.close(pledgeId);
        }
    }
}
