package dev.logast.support;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.observation.Observation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts business outcomes with one line next to the log line that reports them.
 * <pre>{@code
 * log.info("Bill request closed");
 * Outcomes.count("bill.request", "closed");     // counter bill.request{outcome="closed"}
 * Outcomes.tag("skipped");                      // outcome="skipped" on the timer already running (same thread)
 * }</pre>
 * Counters go to Micrometer's global registry, which Spring Boot connects to the application's registry
 * ({@code management.metrics.use-global-registry}, on by default). Each counter is created once and cached, so the
 * call is cheap on every request. One meter per subject: its outcomes are values of the {@code outcome} tag.
 */
public final class Outcomes {
    private static final Map<String, Counter> COUNTERS = new ConcurrentHashMap<>();

    private Outcomes() {
    }

    public static void count(String name, String outcome) {
        COUNTERS.computeIfAbsent(name + '\u0000' + outcome,
                k -> Counter.builder(name).tag("outcome", outcome).register(Metrics.globalRegistry)).increment();
    }

    /** Tags the observation running on this thread (an HTTP request, a job, an ObservedContext); no-op without one. */
    public static void tag(String outcome) {
        Observation current = ObservationRegistryHolder.get().getCurrentObservation();
        if (current != null) {
            current.lowCardinalityKeyValue("outcome", outcome);
        }
    }
}
