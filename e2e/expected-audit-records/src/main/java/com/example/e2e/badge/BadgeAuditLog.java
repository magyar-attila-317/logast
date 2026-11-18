package com.example.e2e.badge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Writes audit records as log lines on the audit logger: complete, and out the moment they are written. */
@Component
public class BadgeAuditLog {
    private static final Logger AUDIT = LoggerFactory.getLogger("audit");

    public void record(String action, String badgeId, String actor, String outcome) {
        AUDIT.info("Badge audit record [action={}, badgeId={}, actor={}, outcome={}]", action, badgeId, actor, outcome);
    }
}
