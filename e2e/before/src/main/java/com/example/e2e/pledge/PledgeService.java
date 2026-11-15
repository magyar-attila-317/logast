package com.example.e2e.pledge;

import com.example.e2e.audit.AuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** pledgeId passed down a chain, logged in free-form messages on every level. */
@Service
public class PledgeService {
    private static final Logger log = LoggerFactory.getLogger(PledgeService.class);
    private final PledgeSender sender;
    private final PledgeRepository repository;
    private final AuditLog audit;

    public PledgeService(PledgeSender sender, PledgeRepository repository, AuditLog audit) {
        this.sender = sender;
        this.repository = repository;
        this.audit = audit;
    }

    /** Audited only when the delete went through: a failed or rejected attempt leaves no record. */
    public void delete(String pledgeId) {
        repository.close(pledgeId);
        audit.record("pledge.delete", pledgeId, "done");
    }

    /** Audited either way: the failed attempt is recorded too. */
    public void revoke(String pledgeId) {
        try {
            repository.close(pledgeId);
            audit.record("pledge.revoke", pledgeId, "done");
        } catch (RuntimeException e) {
            audit.record("pledge.revoke", pledgeId, "failed");
            throw e;
        }
    }

    public void publish(String pledgeId) {
        log.info("Publishing pledge {}", pledgeId);
        sender.send(pledgeId);
        repository.close(pledgeId);
        log.info("Pledge {} published", pledgeId);
    }

    public void allot(AllotRequest request) {
        log.info("Pledge allotment started [pledgeId={}]", request.code());
        sender.send(request.code());
        log.info("Pledge allotment completed [pledgeId={}, tableId={}]", request.code(), request.tableId());
    }
}
