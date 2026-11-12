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
}
