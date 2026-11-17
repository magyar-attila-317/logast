package com.example.e2e.box;

/** A base class named like audit (as AbstractAuditable): its subclasses are not audit records. */
public abstract class AuditableRecord {
    private String changedBy;

    public String changedBy() {
        return changedBy;
    }
}
