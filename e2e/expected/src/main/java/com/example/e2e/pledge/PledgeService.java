package com.example.e2e.pledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** pledgeId passed down a chain, logged in free-form messages on every level. */
@Service
public class PledgeService {
    private static final Logger log = LoggerFactory.getLogger(PledgeService.class);
    private final PledgeSender sender;
    private final PledgeRepository repository;

    public PledgeService(PledgeSender sender, PledgeRepository repository) {
        this.sender = sender;
        this.repository = repository;
    }

    public void publish(String pledgeId) {
        log.info("Publishing pledge");
        sender.send(pledgeId);
        repository.close(pledgeId);
        log.info("Pledge published");
    }
}
