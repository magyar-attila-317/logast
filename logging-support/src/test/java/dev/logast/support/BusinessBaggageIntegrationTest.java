package dev.logast.support;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #16: with Micrometer Tracing (OpenTelemetry bridge), {@code BusinessBaggage.with} puts the value into baggage, so the
 * next service receives it in the propagated headers, and into MDC; closing removes both.
 */
@SpringBootTest(classes = BusinessBaggageIntegrationTest.App.class, properties = {
        "management.tracing.sampling.probability=1.0",
        "management.tracing.baggage.remote-fields=tenantId",
        "management.tracing.baggage.correlation.fields=tenantId",
        "management.otlp.metrics.export.enabled=false",
        "management.tracing.export.otlp.enabled=false",
        "management.logging.export.otlp.enabled=false"})
class BusinessBaggageIntegrationTest {

    @SpringBootApplication
    static class App {
    }

    @Autowired
    Tracer tracer;
    @Autowired
    Propagator propagator;
    @Autowired
    ApplicationContext context;

    @BeforeEach
    void wired() {
        // BusinessBaggageTest resets the static opener; wire it to this context's Tracer again
        context.getBean("businessBaggageInitializer", SmartInitializingSingleton.class).afterSingletonsInstantiated();
        MDC.clear();
    }

    @Test
    void theValueTravelsInTheHeadersAndSitsInMdcForTheScope() {
        var span = tracer.nextSpan().name("tenant.sync").start();
        try (var ws = tracer.withSpan(span)) {
            try (var baggage = BusinessBaggage.with("tenantId", "t-7")) {
                assertThat(MDC.get("tenantId")).isEqualTo("t-7");
                assertThat(tracer.getBaggage("tenantId").get()).isEqualTo("t-7");
                assertThat(headers(tracer.currentSpan())).as("what an instrumented client sends downstream")
                        .containsEntry("tenantId", "t-7");
            }
            assertThat(MDC.get("tenantId")).isNull();
            var after = tracer.getBaggage("tenantId");
            assertThat(after == null ? null : after.get()).isNull();
            assertThat(headers(tracer.currentSpan())).doesNotContainValue("t-7");
        } finally {
            span.end();
        }
    }

    @Test
    void closingRestoresTheOuterMdcValueAlthoughCorrelationWritesTheEntryToo() {
        var span = tracer.nextSpan().name("tenant.sync").start();
        try (var ws = tracer.withSpan(span)) {
            MDC.put("tenantId", "outer");
            try (var baggage = BusinessBaggage.with("tenantId", "t-7")) {
                assertThat(MDC.get("tenantId")).isEqualTo("t-7");
            }
            assertThat(MDC.get("tenantId")).isEqualTo("outer");
        } finally {
            span.end();
            MDC.clear();
        }
    }

    private Map<String, String> headers(io.micrometer.tracing.Span span) {
        Map<String, String> headers = new HashMap<>();
        propagator.inject(span.context(), headers, Map::put);
        return headers;
    }
}
