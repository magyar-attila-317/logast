package com.example.e2e.audit;

import org.springframework.stereotype.Component;

/** Records who did what to which record (here: kept in memory; a real one writes to an audit store). */
@Component
public class AuditLog {
    private final java.util.List<String> entries = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void record(String action, String target, String outcome) {
        entries.add(action + " " + target + " " + outcome);
    }
}
