package com.example.shop.web;

import com.example.shop.domain.OrderNotFoundException;
import com.example.shop.jobs.AuditTrail;
import com.example.shop.jobs.ExportJobs;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ExecutionException;

/** Admin actions: one audited, one not; a blocking wait on a request thread. */
@RestController
@RequestMapping("/admin")
public class AdminController {
    private final AuditTrail auditTrail;
    private final ExportJobs exports;

    public AdminController(AuditTrail auditTrail, ExportJobs exports) {
        this.auditTrail = auditTrail;
        this.exports = exports;
    }

    @PostMapping("/customers/{customerId}/reset")
    public void resetCustomer(@PathVariable String customerId) { // expect: MISSING_DOMAIN_MDC
        auditTrail.record("customer.reset", customerId);
    }

    @DeleteMapping("/sessions/{sessionId}")
    public void endSession(@PathVariable String sessionId) { // expect: AUDIT_MISSING, ENTRYPOINT_SILENT
        if (sessionId.isBlank()) {
            throw new OrderNotFoundException(sessionId);
        }
    }

    @GetMapping("/exports/{exportId}")
    public String export(@PathVariable String exportId) throws InterruptedException, ExecutionException {
        return exports.start(exportId).get(); // expect: BLOCKING_WAIT_UNBOUNDED
    }
}
