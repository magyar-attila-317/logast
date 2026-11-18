package com.example.e2e.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Records who did what to which record (here: kept in memory; a real one writes to an audit store). */
@Component
public class AuditLog {
    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);
    private final java.util.List<String> entries = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void record(String action, String target, String outcome) {
        entries.add(action + " " + target + " " + outcome);
        log.info("Audit record written [action={}, outcome={}]", action, outcome);
    }

    public void record(String action, String target, String outcome, String actor) {
        entries.add(actor + " " + action + " " + target + " " + outcome);
    }

    /** A diagnostic, not a record (#45): it must stay in the application log. */
    public void checkCapacity(int limit) {
        if (entries.size() > limit) {
            log.warn("Audit store is almost full [limit={}]", limit);
        }
    }
}
