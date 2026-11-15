package com.example.e2e.pledge;

import com.example.e2e.logging.AfterCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** A nightly job looping over a query result. */
@Component
public class PledgeCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(PledgeCleanupJob.class);
    private final PledgeRepository repository;

    public PledgeCleanupJob(PledgeRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void cleanup() {
        log.debug("Cleanup tick");
        log.info("Pledge sweep started");
        var pledgeIds = repository.findOpen();
        for (String pledgeId : pledgeIds) {
            log.debug("Closing stale pledge [pledgeId={}]", pledgeId);
            repository.close(pledgeId);
        }
        AfterCommit.log(() -> log.info("Pledge sweep completed [pledgeCount={}]", pledgeIds.size()));
    }
}
