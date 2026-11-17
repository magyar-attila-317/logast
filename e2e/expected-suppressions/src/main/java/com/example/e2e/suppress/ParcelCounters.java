package com.example.e2e.suppress;

import io.micrometer.core.instrument.MeterRegistry;

class ParcelCounters {
    private final MeterRegistry registry;

    ParcelCounters(MeterRegistry registry) {
        this.registry = registry;
    }

    void shipped() {
        // logast:ignore METRIC_TAG_KEYS_MISMATCH
        registry.counter("parcel.shipped", "outcome", "ok").increment();
    }

    void returned() {
        registry.counter("parcel.returned", "result", "ok").increment(); // logast:ignore METRIC_TAG_KEY
    }

    void held() {
        // logast:ignore METRIC_REGISTERED_PER_CALL, METRIC_TAG_KEY -- the tag is read by an old dashboard
        registry.counter("parcel.held", "result", "ok").increment();
    }

    void lost() {
        // logast:ignore
        registry.counter("parcel.lost", "result", "ok").increment();
    }
}
