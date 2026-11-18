package dev.logast.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #16: BusinessBaggage without Spring: MDC only when no Tracer is wired, restored on close, null-safe. */
class BusinessBaggageTest {

    @BeforeEach
    @AfterEach
    void noTracer() {
        BusinessBaggage.opener(null);
        MDC.clear();
    }

    @Test
    void withoutATracerOnlyTheMdcEntryIsSetAndRestored() {
        MDC.put("tenantId", "outer");
        try (var baggage = BusinessBaggage.with("tenantId", 42)) {
            assertThat(MDC.get("tenantId")).isEqualTo("42");
        }
        assertThat(MDC.get("tenantId")).isEqualTo("outer");
        try (var baggage = BusinessBaggage.with("regionId", "eu")) {
            assertThat(MDC.get("regionId")).isEqualTo("eu");
        }
        assertThat(MDC.get("regionId")).isNull();
    }

    @Test
    void aNullValueKeepsTheOuterValue() {
        List<String> opened = new ArrayList<>();
        BusinessBaggage.opener((k, v) -> {
            opened.add(k);
            return null;
        });
        MDC.put("tenantId", "outer");
        try (var baggage = BusinessBaggage.with("tenantId", null)) {
            assertThat(MDC.get("tenantId")).isEqualTo("outer");
        }
        assertThat(MDC.get("tenantId")).isEqualTo("outer");
        assertThat(opened).as("no baggage for a null value").isEmpty();
    }

    @Test
    void aKeyIsRequired() {
        assertThatThrownBy(() -> BusinessBaggage.with(null, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BusinessBaggage.with(" ", "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void closingEndsTheBaggageScopeBeforeRestoringTheMdc() {
        List<String> events = new ArrayList<>();
        BusinessBaggage.opener((k, v) -> {
            events.add("open " + k + "=" + v);
            return () -> events.add("close " + k + " (mdc " + MDC.get(k) + ")");
        });
        try (var baggage = BusinessBaggage.with("tenantId", "t-7")) {
            events.add("inside (mdc " + MDC.get("tenantId") + ")");
        }
        assertThat(events).containsExactly("open tenantId=t-7", "inside (mdc t-7)", "close tenantId (mdc t-7)");
        assertThat(MDC.get("tenantId")).isNull();
    }
}
