package com.example.e2e.pledge;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** An hourly job that returns how many rows it deleted (#334: the count is "deleted", not the method's name). */
@Component
public class PledgeExpiryJob {
    private final PledgeRepository repository;

    public PledgeExpiryJob(PledgeRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "0 30 * * * *")
    public int expire() {
        return repository.deleteExpired();
    }
}
