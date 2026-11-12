package dev.logast.support;

import org.slf4j.MDC;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * MDC entries for the duration of a try-with-resources block. Unlike {@code MDC.putCloseable}, closing
 * restores the value each key had before (instead of removing it), so nested scopes are safe.
 *
 * <pre>{@code
 * try (var mdc = MdcScope.of("order.id", orderId).and("customer.id", customerId)) {
 *     ...
 * }
 * }</pre>
 * Null values are ignored, so an outer value is never cleared by accident.
 */
public final class MdcScope implements AutoCloseable {
    private final Deque<String[]> previous = new ArrayDeque<>();

    private MdcScope() {
    }

    public static MdcScope empty() {
        return new MdcScope();
    }

    /**
     * Opens a scope with one or more entries: {@code MdcScope.of("order.id", id, "customer.id", customerId)}.
     * All values are evaluated by Java before anything touches the MDC, so an exception while computing a value
     * cannot leave half of the entries behind (which a chained {@code of(..).and(..)} could).
     */
    public static MdcScope of(String key, Object value, Object... moreKeyValues) {
        if (moreKeyValues.length % 2 != 0) {
            throw new IllegalArgumentException("MdcScope.of expects key/value pairs");
        }
        for (int i = 0; i < moreKeyValues.length; i += 2) {
            if (!(moreKeyValues[i] instanceof String)) {
                throw new IllegalArgumentException("MdcScope key at position " + (i + 2) + " is not a String");
            }
        }
        MdcScope scope = new MdcScope().and(key, value);
        for (int i = 0; i < moreKeyValues.length; i += 2) {
            scope.and((String) moreKeyValues[i], moreKeyValues[i + 1]);
        }
        return scope;
    }

    /**
     * Adds an entry to an open scope (restored on close). Prefer passing all entries to {@link #of}: in a chain
     * {@code of(..).and(expr)} a throwing {@code expr} leaves the first entry set with no scope to close it.
     */
    public MdcScope and(String key, Object value) {
        if (value != null) {
            previous.push(new String[]{key, MDC.get(key)});
            MDC.put(key, String.valueOf(value));
        }
        return this;
    }

    @Override
    public void close() {
        while (!previous.isEmpty()) {
            String[] entry = previous.pop();
            if (entry[1] == null) {
                MDC.remove(entry[0]);
            } else {
                MDC.put(entry[0], entry[1]);
            }
        }
    }
}
