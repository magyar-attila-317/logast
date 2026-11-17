package com.example.shop.support;

import org.slf4j.Logger;

import java.util.Collection;

/** A generic helper that logs for its callers. */
public final class LogHelper {
    private LogHelper() {
    }

    public static void logCollection(Logger log, String label, Collection<?> collection) { // expect: LOG_HELPER
        if (log.isTraceEnabled()) {
            log.trace("{} [elements={}]", label, collection);
        } else {
            log.debug("{} [count={}]", label, collection.size());
        }
    }

    /** #58 f: near miss, redaction is a cross-cutting reason to log through a helper. */
    public static void logMaskedEmail(Logger log, String label, String email) { // expect-not: LOG_HELPER
        log.info("{} [recipient={}]", label, email.replaceAll("(?<=.).(?=[^@]*@)", "*"));
    }
}
