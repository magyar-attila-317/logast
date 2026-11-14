package com.example.e2e.pledge;

import com.example.e2e.logging.MdcScope;
import com.example.e2e.logging.Outcomes;
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
        Outcomes.count("pledge", "published");
    }

    public void allot(AllotRequest request) {
        try (var mdc = MdcScope.of("pledgeId", request.code())) {
            log.info("Pledge allotment started");
            sender.send(request.code());
            log.info("Pledge allotment completed [tableId={}]", request.tableId());
            Outcomes.count("pledge.allotment", "completed");
        }
    }
}
