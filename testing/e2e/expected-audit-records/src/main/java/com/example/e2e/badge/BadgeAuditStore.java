package com.example.e2e.badge;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Writes audit records to a store (here: in memory). */
@Component
public class BadgeAuditStore {
    private final List<String> entries = new CopyOnWriteArrayList<>();

    /** Neither the outcome nor the request it belongs to. */
    public void note(String action, String badgeId, String actor) {
        entries.add(actor + " " + action + " " + badgeId);
    }

    /** Complete: outcome and trace id passed in. */
    public void record(String action, Object details, String actor, String outcome, String traceId) {
        entries.add(traceId + " " + actor + " " + action + " " + details + " " + outcome);
    }

    /** Complete, reading the actor and the trace id itself. */
    public void recordCurrent(String action, String badgeId, String outcome) {
        String actor = SecurityContextHolder.getContext().getAuthentication().getName();
        entries.add(MDC.get("traceId") + " " + actor + " " + action + " " + badgeId + " " + outcome);
    }
}
