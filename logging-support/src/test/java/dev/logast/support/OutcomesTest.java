package dev.logast.support;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutcomesTest {
    @Test
    void countsPerSubjectAndOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            Outcomes.count("bill.request", "closed");
            Outcomes.count("bill.request", "closed");
            Outcomes.count("bill.request", "rejected");
            assertThat(registry.get("bill.request").tag("outcome", "closed").counter().count()).isEqualTo(2);
            assertThat(registry.get("bill.request").tag("outcome", "rejected").counter().count()).isEqualTo(1);
        } finally {
            Metrics.removeRegistry(registry);
        }
    }

    @Test
    void tagWithoutObservationIsANoOp() {
        Outcomes.tag("skipped");
    }
}
