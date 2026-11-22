package dev.logast.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects MDC entries that outlive the unit of work that set them. Each distinct leak (boundary + keys) is
 * logged once at WARN so the culprit can be found without flooding the logs.
 */
public final class MdcHygiene {
    private static final Logger log = LoggerFactory.getLogger(MdcHygiene.class);
    private static final int MAX_REPORTED = 500;
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private static volatile Set<String> ambientKeys = Set.of("traceId", "spanId");

    private MdcHygiene() {
    }

    /** Keys maintained by tracing; never reported as leaks. */
    public static void ambientKeys(Set<String> keys) {
        ambientKeys = Set.copyOf(keys);
    }

    /** Non-ambient keys present in {@code after} that were not present in {@code before}. */
    public static Set<String> leaked(Map<String, String> before, Map<String, String> after) {
        Set<String> out = new TreeSet<>();
        if (after != null) {
            for (String key : after.keySet()) {
                if (!ambientKeys.contains(key) && (before == null || !before.containsKey(key))) {
                    out.add(key);
                }
            }
        }
        return out;
    }

    /** Non-ambient keys in a map that should be empty (e.g. a pooled thread before a new request). */
    public static Set<String> leftovers(Map<String, String> mdc) {
        return leaked(null, mdc);
    }

    public static void report(String boundary, Set<String> keys) {
        if (keys.isEmpty() || REPORTED.size() >= MAX_REPORTED || !REPORTED.add(boundary + keys)) {
            return;
        }
        log.warn("MDC entries leaked past {} [keys={}]; scope them with MdcScope (try-with-resources)", boundary, keys);
    }

    static void resetForTests() {
        REPORTED.clear();
    }
}
