package com.example.e2e.pledge;

import com.example.e2e.metrics.BusinessMetrics;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Log-and-rethrow on two paths (#46): find's exception ends in an @ExceptionHandler that maps it without logging, so
 * its log line is the only record and must stay; reload only runs from a @Scheduled job, which Spring logs.
 */
@Service
public class PledgeLookup {
    private static final Logger log = LoggerFactory.getLogger(PledgeLookup.class);
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String find(String pledgeId) {
        try {
            return load(pledgeId);
        } catch (IllegalStateException e) {
            log.warn("Pledge lookup failed", e);
            BusinessMetrics.tagFailed();
            throw new PledgeMissing(pledgeId, e);
        }
    }

    public void reload() {
        try {
            cache.clear();
            load("all");
        } catch (IllegalStateException e) {
            throw e;
        }
    }

    private String load(String pledgeId) {
        String value = cache.get(pledgeId);
        if (value == null) {
            throw new IllegalStateException("not cached: " + pledgeId);
        }
        return value;
    }
}
