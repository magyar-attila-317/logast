package com.example.shop.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Metric correctness cases: tag vocabulary, tag key sets, weak gauges, per-item registration. */
@Component
public class StockMetrics {
    static final String RESERVED = "stock.reserved";
    private final MeterRegistry registry;
    private final AtomicInteger pending;

    public StockMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.pending = registry.gauge("stock.pending", new AtomicInteger());   // kept in a field: fine
    }

    public void reserved(String channel) {
        Counter.builder(RESERVED).tag("channel", channel).register(registry).increment(); // expect: METRIC_TAG_KEYS_MISMATCH
    }

    public void reservationFailed(String channel, String reason) {
        Counter.builder(RESERVED).tag("channel", channel).tag("reason", reason) // expect: METRIC_TAG_KEYS_MISMATCH
                .register(registry).increment();
    }

    public void restocked(List<String> skus) {
        for (String sku : skus) {
            registry.counter("stock.restocked", "result", "ok").increment(); // expect: METRIC_REGISTERED_PER_CALL, METRIC_TAG_KEY
        }
    }

    public void trackQueue() {
        AtomicInteger queue = new AtomicInteger();
        registry.gauge("stock.queue", queue); // expect: METRIC_GAUGE_WEAK_REF
    }
}
