package com.example.e2e.suppress;

// logast:ignore-file METRIC_TAG_KEY

import io.micrometer.core.instrument.MeterRegistry;

class ParcelGauges {
    private final MeterRegistry registry;

    ParcelGauges(MeterRegistry registry) {
        this.registry = registry;
    }

    void sorted() {
        registry.counter("parcel.sorted", "result", "ok").increment();
    }
}
