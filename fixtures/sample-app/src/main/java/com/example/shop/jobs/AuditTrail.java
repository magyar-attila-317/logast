package com.example.shop.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/** Legacy pattern: puts in try, MDC.clear() in finally (wipes the caller's context too). */
@Component
public class AuditTrail {
    private static final Logger log = LoggerFactory.getLogger(AuditTrail.class);

    public void record(String action, String tenantId) {
        try {
            MDC.put("audit.action", action); // expect: MDC_NOT_CLOSEABLE, MDC_KEY_ALIAS
            MDC.put("tenantId", tenantId);
            log.info("Audit entry written");
        } finally {
            MDC.clear();
        }
    }
}
