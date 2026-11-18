package com.example.shop.service;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.annotation.Observed;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Hand-made observations: an annotation Spring Boot does not process yet, one started by hand and left open. */
@Component
public class StockReservations {
    private final ObservationRegistry registry;
    private final Set<String> reserved = new HashSet<>();

    public StockReservations(ObservationRegistry registry) {
        this.registry = registry;
    }

    @Observed(name = "stock.reserve")
    public void reserve(String sku) { // expect: OBSERVED_ANNOTATION_INACTIVE
        reserved.add(sku);
    }

    public void release(String sku) {
        Observation observation = Observation.start("stock.release", registry); // expect: SPAN_NOT_CLOSED
        reserved.remove(sku);
        observation.stop();
    }

    public void restock(String sku) {
        Observation observation = Observation.start("stock.restock", registry); // expect-not: SPAN_NOT_CLOSED
        try (Observation.Scope scope = observation.openScope()) {
            reserved.add(sku);
        } catch (RuntimeException e) {
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }
}
