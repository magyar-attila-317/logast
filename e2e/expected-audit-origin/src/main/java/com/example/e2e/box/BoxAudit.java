package com.example.e2e.box;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/** Writes audit records (here: in memory). */
@Component
public class BoxAudit {
    private final List<String> entries = new CopyOnWriteArrayList<>();

    public void record(String action, Object target, Object by) {
        entries.add(by + " " + action + " " + target);
    }

    public void record(BoxAuditEntry entry) {
        entries.add(entry.toString());
    }
}
