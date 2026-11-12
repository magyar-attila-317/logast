package com.example.shop.support;

import org.slf4j.Logger;

import java.util.Collection;

/** A generic helper that logs for its callers. */
public final class LogHelper {
    private LogHelper() {
    }

    public static void logCollection(Logger log, String message, Collection<?> items) { // expect: LOG_HELPER
        log.info("{}: {} items", message, items.size()); // expect: LOG_FORMAT
    }
}
