package com.example.e2e.stock;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.stereotype.Component;

@Component
class StockRouting {
    private final ObservationRegistry registry;
    private final Tracer tracer;

    StockRouting(ObservationRegistry registry, Tracer tracer) {
        this.registry = registry;
        this.tracer = tracer;
    }

    String route(String stockCode) {
        Observation observation = Observation.start("stock.route", registry);
        String lane = "lane-" + stockCode;
        observation.stop();
        return lane;
    }

    String weigh(String stockCode) {
        Observation observation = Observation.createNotStarted("stock.weigh", registry).start();
        try (Observation.Scope scope = observation.openScope()) {
            return "weight-" + stockCode;
        } finally {
            observation.stop();
        }
    }

    String plan(String stockCode) {
        Span span = tracer.nextSpan().name("stock.plan").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            return "plan-" + stockCode;
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    String label(String stockCode) {
        return Observation.createNotStarted("stock.label", registry).observe(() -> "label-" + stockCode);
    }
}
