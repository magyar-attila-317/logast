package dev.logast.support;

import java.util.function.BiFunction;

/**
 * A business id as trace baggage for the duration of a try-with-resources block: it travels to the services this one
 * calls (in the {@code baggage} header, W3C, or the headers named by {@code management.tracing.baggage.remote-fields}),
 * and it is in this service's MDC. Closing restores both to what they were before.
 *
 * <pre>{@code
 * try (var baggage = BusinessBaggage.with("tenantId", tenantId)) {
 *     client.post(...);   // the next service receives tenantId with the trace
 * }
 * }</pre>
 * A null value opens an empty scope (an outer value is kept). Without Micrometer Tracing (no Tracer bean), only the
 * MDC entry is set. logast never adds this call: every downstream service, third parties included, receives the
 * value, so put no personal data in it (docs/packs/tracing.md, "Business ids across services").
 */
public final class BusinessBaggage implements AutoCloseable {
    private static volatile BiFunction<String, String, Runnable> opener = (key, value) -> null;

    private final Runnable baggage;
    private final MdcScope mdc;

    private BusinessBaggage(Runnable baggage, MdcScope mdc) {
        this.baggage = baggage;
        this.mdc = mdc;
    }

    public static BusinessBaggage with(String key, Object value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("BusinessBaggage needs a key");
        }
        if (value == null) {
            return new BusinessBaggage(null, MdcScope.empty());
        }
        String text = String.valueOf(value);
        // MDC first: with management.tracing.baggage.correlation.fields, opening the baggage writes the MDC entry too,
        // and the scope must remember the value from before, not that one
        MdcScope mdc = MdcScope.of(key, text);
        try {
            return new BusinessBaggage(opener.apply(key, text), mdc);
        } catch (RuntimeException e) {
            mdc.close();
            throw e;
        }
    }

    @Override
    public void close() {
        try {
            if (baggage != null) {
                baggage.run();
            }
        } finally {
            mdc.close();
        }
    }

    /**
     * Set by {@link BusinessBaggageConfiguration} when a Tracer is present: puts {@code key=value} into baggage and returns
     * what closes that scope (null: nothing to close).
     */
    public static void opener(BiFunction<String, String, Runnable> o) {
        opener = o == null ? (key, value) -> null : o;
    }
}
