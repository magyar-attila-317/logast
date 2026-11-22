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

import java.security.Principal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    public void resetCustomer(@PathVariable String customerId) { // expect: MISSING_DOMAIN_MDC // expect-not: AUDIT_MISSING
        auditTrail.record("customer.reset", customerId); // expect: AUDIT_RECORD_INCOMPLETE
    }

    /** #50: the attempt recorded first, the actor read from the principal: complete, and failures recorded too. */
    @PostMapping("/customers/{customerId}/unlock")
    public void unlockCustomer(@PathVariable String customerId, Principal principal) { // expect: MISSING_DOMAIN_MDC, ENTRYPOINT_SILENT // expect-not: AUDIT_FAILURES_UNRECORDED
        auditTrail.record("customer.unlock", customerId, principal.getName()); // expect-not: AUDIT_RECORD_INCOMPLETE
        if (customerId.isBlank()) {
            throw new OrderNotFoundException(customerId);
        }
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

    /** #82: the bounded wait completes both futures first, so their join() cannot block. */
    @GetMapping("/exports/{exportId}/pair")
    public String exportPair(@PathVariable String exportId) throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<String> head = CompletableFuture.completedFuture(exportId.strip());
        CompletableFuture<String> tail = CompletableFuture.completedFuture(exportId.toUpperCase());
        CompletableFuture.allOf(head, tail).get(5, TimeUnit.SECONDS);
        return head.join() + tail.join(); // expect-not: BLOCKING_WAIT_UNBOUNDED
    }
}
