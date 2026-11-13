package dev.logast;

import dev.logast.rules.MetricsMap;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** logast metrics: what is measured, by what, and which logged outcomes no metric counts. */
class MetricsMapTest {
    @Test
    void mapsEntrypointsMetersAndUncountedOutcomes() throws Exception {
        MetricsMap.Map m = MetricsMap.of(Analysis.run(Fixtures.root(), Fixtures.classpath(), Fixtures.policy()));
        assertThat(m.measured()).anySatisfy(x -> assertThat(x.query()).startsWith("http.server.requests{method=\"POST\""));
        assertThat(m.measured()).anySatisfy(x -> assertThat(x.query()).startsWith("tasks.scheduled.execution{"));
        assertThat(m.unmeasuredEntrypoints()).anySatisfy(e -> assertThat(e).startsWith("event "));
        assertThat(m.outcomes()).anySatisfy(u -> {
            assertThat(u.message()).isEqualTo("Audit skipped");
            assertThat(u.event()).isEqualTo("skipped");
            assertThat(u.counter()).isEqualTo("Counter.builder(\"audit\").tag(\"outcome\", \"skipped\").register(meterRegistry).increment()");
        });
        assertThat(m.identity()).containsEntry("spring.application.name", "shop");
    }
}
