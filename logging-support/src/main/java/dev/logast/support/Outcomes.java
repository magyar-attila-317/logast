package dev.logast.support;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
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
 *
 * log.info("Coupons saved [rows={}]", rows);
 * Outcomes.count("coupons", "saved", "rows", rows);   // + coupons{outcome="saved"} by 1, coupons.rows{…} by rows
 * Outcomes.record("coupons", "saved", "rows", rows);  // + coupons{…} by 1, rows as one sample of coupons.rows{…}
 * }</pre>
 * Counters go to Micrometer's global registry, which Spring Boot connects to the application's registry
 * ({@code management.metrics.use-global-registry}, on by default). Each counter is created once and cached, so the
 * call is cheap on every request. One meter per subject: its outcomes are values of the {@code outcome} tag.
 */
public final class Outcomes {
    private static final Map<String, Counter> COUNTERS = new ConcurrentHashMap<>();
    private static final Map<String, DistributionSummary> SUMMARIES = new ConcurrentHashMap<>();

    private Outcomes() {
    }

    public static void count(String name, String outcome) {
        COUNTERS.computeIfAbsent(name + '\u0000' + outcome,
                k -> Counter.builder(name).tag("outcome", outcome).register(Metrics.globalRegistry)).increment();
    }

    /**
     * Counts the outcome once and adds {@code amount} to the running total {@code name.amountName} (rows written, items
     * sent): totals and throughput over time. A null amount counts the outcome only.
     */
    public static void count(String name, String outcome, String amountName, Number amount) {
        count(name, outcome);
        if (amount != null) {
            String total = name + "." + amountName;
            COUNTERS.computeIfAbsent(total + '\u0000' + outcome,
                    k -> Counter.builder(total).tag("outcome", outcome).register(Metrics.globalRegistry)).increment(amount.doubleValue());
        }
    }

    /**
     * Counts the outcome once and records {@code amount} as one sample of {@code name.amountName} (the size of this
     * run): average, maximum and, where the registry is set up for it, percentiles. A null amount counts the outcome only.
     */
    public static void record(String name, String outcome, String amountName, Number amount) {
        count(name, outcome);
        if (amount != null) {
            String sizes = name + "." + amountName;
            SUMMARIES.computeIfAbsent(sizes + '\u0000' + outcome,
                    k -> DistributionSummary.builder(sizes).tag("outcome", outcome).register(Metrics.globalRegistry)).record(amount.doubleValue());
        }
    }

    /** Tags the observation running on this thread (an HTTP request, a job, an ObservedContext); no-op without one. */
    public static void tag(String outcome) {
        Observation current = ObservationRegistryHolder.get().getCurrentObservation();
        if (current != null) {
            current.lowCardinalityKeyValue("outcome", outcome);
        }
    }
}
