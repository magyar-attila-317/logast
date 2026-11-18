package com.example.e2e.badge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BadgeService {
    private final Map<String, String> badges = new ConcurrentHashMap<>();
    private final BadgeAuditLog auditLog;
    private final BadgeAuditStore auditStore;

    public BadgeService(BadgeAuditLog auditLog, BadgeAuditStore auditStore) {
        this.auditLog = auditLog;
        this.auditStore = auditStore;
    }

    /** The record is a log line, written before the transaction commits. */
    @Transactional
    public void revoke(String badgeId, String actor) {
        badges.remove(badgeId);
        auditLog.record("badge.revoke", badgeId, actor, "done");
    }

    /** The failure is recorded in the catch: the transaction rolls back and the record says so. */
    @Transactional
    public void suspend(String badgeId, String actor) {
        try {
            badges.put(badgeId, "SUSPENDED");
        } catch (RuntimeException e) {
            auditLog.record("badge.suspend", badgeId, actor, "failed");
            throw e;
        }
    }

    /** A record in the store, not a log line: logast cannot tell it leaves the transaction. */
    @Transactional
    public void renew(String badgeId, String actor) {
        badges.put(badgeId, "ACTIVE");
        auditStore.record("badge.renew", badgeId, actor, "done", MDC.get("traceId"));
    }

    /** Neither outcome nor trace id in the record. */
    public void lock(String badgeId, String actor) {
        badges.put(badgeId, "LOCKED");
        auditStore.note("badge.lock", badgeId, actor);
    }

    /** The new PIN and the request holding the PIN go into the record; the token id does not leak it. */
    public void changePin(String badgeId, String newPin, String tokenId, BadgeRequest request, String actor) {
        badges.put(badgeId, newPin);
        auditStore.record("badge.pin", newPin, actor, "done", MDC.get("traceId"));
        auditStore.record("badge.issue", request, actor, "done", MDC.get("traceId"));
        auditStore.record("badge.token", tokenId, actor, "done", MDC.get("traceId"));
    }

    /** On another thread: the store reads the security context there, which is empty. */
    @Async
    public void expire(String badgeId) {
        badges.remove(badgeId);
        auditStore.recordCurrent("badge.expire", badgeId, "done");
    }

    /** On another thread, with the actor read on the request thread and passed in. */
    @Async
    public void archive(String badgeId, String actor) {
        badges.remove(badgeId);
        auditStore.record("badge.archive", badgeId, actor, "done", MDC.get("traceId"));
    }
}
