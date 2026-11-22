package dev.logast.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class MdcScopeTest {
    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void restoresPreviousValuesInsteadOfRemoving() {
        MDC.put("order.id", "outer");
        try (var mdc = MdcScope.of("order.id", "inner").and("customer.id", 42)) {
            assertThat(MDC.get("order.id")).isEqualTo("inner");
            assertThat(MDC.get("customer.id")).isEqualTo("42");
        }
        assertThat(MDC.get("order.id")).isEqualTo("outer");
        assertThat(MDC.get("customer.id")).isNull();
    }

    @Test
    void nullValueKeepsOuterValue() {
        MDC.put("order.id", "outer");
        try (var mdc = MdcScope.of("order.id", null)) {
            assertThat(MDC.get("order.id")).isEqualTo("outer");
        }
        assertThat(MDC.get("order.id")).isEqualTo("outer");
    }

    private static String boom() {
        throw new IllegalStateException("value lookup failed");
    }

    @Test
    void failingValueLeavesNothingBehind() {
        try (var mdc = MdcScope.of("order.id", "o-1", "customer.id", boom())) {
            throw new AssertionError("unreachable");
        } catch (IllegalStateException expected) {
            // the second value threw before the scope existed
        }
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void sameKeyTwiceRestoresInOrder() {
        MDC.put("k", "outer");
        try (var mdc = MdcScope.of("k", "a", "k", "b")) {
            assertThat(MDC.get("k")).isEqualTo("b");
        }
        assertThat(MDC.get("k")).isEqualTo("outer");
    }

    @Test
    void clearInsideScopeStillRestoresOuterValues() {
        MDC.put("request.id", "r-1");
        try (var mdc = MdcScope.of("request.id", "r-2", "order.id", "o-1")) {
            MDC.clear(); // legacy code inside the scope
        }
        assertThat(MDC.getCopyOfContextMap()).containsExactly(java.util.Map.entry("request.id", "r-1"));
    }

    @Test
    void exceptionInBodyRestores() {
        try (var mdc = MdcScope.of("order.id", "o-1")) {
            throw new IllegalStateException("body failed");
        } catch (IllegalStateException expected) {
            // ignored
        }
        assertThat(MDC.get("order.id")).isNull();
    }
}
