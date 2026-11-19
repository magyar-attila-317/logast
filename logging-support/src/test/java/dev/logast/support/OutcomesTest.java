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

    /**
     * #118: registered at startup, the series exist at 0 before any event, in a registry bound to the global one later
     * too (Micrometer adds the global registry's meters to each registry as it is bound); counting continues on them.
     */
    @Test
    void registeredCountersExistAtZeroBeforeTheFirstEvent() {
        Outcomes.register("parcel.dispatch", "sent");
        Outcomes.registerTotal("parcel.batch", "sent", "items");
        Outcomes.registerPerRun("parcel.run", "done", "items");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);   // the application's registry, bound after the catalog registered
        try {
            assertThat(registry.get("parcel.dispatch").tag("outcome", "sent").counter().count()).isZero();
            assertThat(registry.get("parcel.batch").tag("outcome", "sent").counter().count()).isZero();
            assertThat(registry.get("parcel.batch.items").tag("outcome", "sent").counter().count()).isZero();
            assertThat(registry.get("parcel.run.items").tag("outcome", "done").summary().count()).isZero();
            Outcomes.count("parcel.dispatch", "sent");
            assertThat(registry.get("parcel.dispatch").tag("outcome", "sent").counter().count()).as("the same series").isEqualTo(1);
        } finally {
            Metrics.removeRegistry(registry);
        }
    }

    @Test
    void tagWithoutObservationIsANoOp() {
        Outcomes.tag("skipped");
    }
}
