package com.example.shop.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class OrderMetrics {
    private final MeterRegistry registry;

    public OrderMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void placed(String orderId, String channel) {
        registry.counter("purchasesPlaced", // expect: METRIC_NAMING // expect-not: METRIC_REGISTERED_PER_CALL
                "orderId", orderId, // expect: METRIC_HIGH_CARDINALITY_TAG
                "channel", channel).increment(); // expect-not: METRIC_HIGH_CARDINALITY_TAG
    }
}
