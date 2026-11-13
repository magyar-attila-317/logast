package com.example.e2e.edge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A home-made log helper, as some services have. */
public final class EdgeLogHelper {
    private static final Logger log = LoggerFactory.getLogger(EdgeLogHelper.class);

    private EdgeLogHelper() {
    }

    public static void info(String message, Object... args) {
        log.info(message, args);
    }
}
