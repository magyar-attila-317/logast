package com.example.shop.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/** Legacy pattern: puts in try, MDC.clear() in finally (wipes the caller's context too). */
@Component
public class AuditTrail {
    private static final Logger log = LoggerFactory.getLogger(AuditTrail.class); // expect: AUDIT_LOG_MIXED
    private final java.util.List<String> entries = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void record(String action, String tenantId) { // expect: AUDIT_RECORD_INCOMPLETE
        try {
            MDC.put("audit.action", action); // expect: MDC_NOT_CLOSEABLE, MDC_KEY_ALIAS, MDC_CLEAR
            MDC.put("tenantId", tenantId);
            log.info("Audit entry written");
        } finally {
            MDC.clear();
        }
    }

    public void record(String action, String customerId, String actor) { // expect: AUDIT_RECORD_INCOMPLETE
        entries.add(actor + " " + action + " " + customerId);
    }
}
