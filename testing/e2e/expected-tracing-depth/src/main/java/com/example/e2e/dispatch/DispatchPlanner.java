package com.example.e2e.dispatch;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.annotation.Observed;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DispatchPlanner {
    private static final Logger log = LoggerFactory.getLogger(DispatchPlanner.class);
    private final ObservationRegistry registry;
    private final Tracer tracer;

    DispatchPlanner(ObservationRegistry registry, Tracer tracer) {
        this.registry = registry;
        this.tracer = tracer;
    }

    @Observed(name = "dispatch.plan")
    public void plan() {
        log.info("Dispatch planning started");
        log.info("Dispatch planning completed");
    }

    public String route(String dispatchId, String lane) {
        return Observation.createNotStarted("dispatch.route." + dispatchId, registry)
                .lowCardinalityKeyValue("dispatch.lane", lane)
                .observe(() -> lane + "-" + dispatchId);
    }

    public String load(String orderId, String lane) {
        return Observation.createNotStarted("dispatch.load", registry)
                .highCardinalityKeyValue("dispatch.lane", lane)
                .lowCardinalityKeyValue("order", orderId)
                .observe(() -> "load-" + orderId);
    }

    public String pick(String dispatchId) {
        Span span = tracer.nextSpan().name("dispatch.pick-%s".formatted(dispatchId)).start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            return "pick-" + dispatchId;
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    public String weigh(String dispatchId, String kind) {
        return Observation.createNotStarted("dispatch.weigh." + kind, registry)
                .highCardinalityKeyValue("dispatch.id", dispatchId)
                .observe(() -> "weight-" + dispatchId);
    }
}
