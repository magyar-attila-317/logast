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
 * log.info("Pledges saved [rows={}]", rows);
 * Outcomes.count("pledges", "saved", "rows", rows);   // + pledges{outcome="saved"} by 1, pledges.rows{…} by rows
 * Outcomes.record("pledges", "saved", "rows", rows);  // + pledges{…} by 1, rows as one sample of pledges.rows{…}
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

    /**
     * Registers {@code name{outcome}} at 0 without counting (#118): the series exists from the first scrape, so a
     * dashboard shows 0 instead of "no data", {@code increase()} has its first sample, and an alert on "0 for an hour"
     * can fire. The metrics catalog calls it at startup for every outcome it knows.
     */
    public static void register(String name, String outcome) {
        counter(name, outcome);
    }

    /** {@link #register} plus the running total {@code name.amountName{outcome}} of {@link #count(String, String, String, Number)}. */
    public static void registerTotal(String name, String outcome, String amountName) {
        counter(name, outcome);
        counter(name + "." + amountName, outcome);
    }

    /** {@link #register} plus the per-run summary {@code name.amountName{outcome}} of {@link #record}. */
    public static void registerPerRun(String name, String outcome, String amountName) {
        counter(name, outcome);
        String sizes = name + "." + amountName;
        SUMMARIES.computeIfAbsent(sizes + '\u0000' + outcome,
                k -> DistributionSummary.builder(sizes).tag("outcome", outcome).register(Metrics.globalRegistry));
    }

    private static Counter counter(String name, String outcome) {
        return COUNTERS.computeIfAbsent(name + '\u0000' + outcome,
                k -> Counter.builder(name).tag("outcome", outcome).register(Metrics.globalRegistry));
    }

    /** Tags the observation running on this thread (an HTTP request, a job, an ObservedContext); no-op without one. */
    public static void tag(String outcome) {
        Observation current = ObservationRegistryHolder.get().getCurrentObservation();
        if (current != null) {
            current.lowCardinalityKeyValue("outcome", outcome);
        }
    }
}
