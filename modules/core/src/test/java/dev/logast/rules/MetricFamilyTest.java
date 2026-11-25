package dev.logast.rules;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** #273: outcomes of one shape share one metric, told apart by a kind tag. */
class MetricFamilyTest {
    private static MetricsMap.Gap gap(String name, String event, String file) {
        var u = new MetricsMap.Unmeasured("M.m", file, 1, "error", name + " " + event, event, List.of(), "low", null,
                "Counter.builder(\"" + name + "\")");
        return new MetricsMap.Gap(u, null, null, name, false, Map.of());
    }

    @Test
    void callSitesOfOneShapeShareAMetric() {
        List<MetricsMap.Gap> out = MetricsMap.families(List.of(
                gap("thing.created.publish", "failed", "src/a/Handlers.java"),
                gap("thing.updated.publish", "failed", "src/a/Handlers.java"),
                gap("thing.price.changed.publish", "failed", "src/a/PriceHandler.java"),
                gap("order.shipped", "failed", "src/a/Orders.java")));
        assertThat(out).extracting(MetricsMap.Gap::name)
                .containsExactly("thing.publish", "thing.publish", "thing.publish", "order.shipped");
        assertThat(out).extracting(MetricsMap.Gap::kind).containsExactly("created", "updated", "price.changed", "");
        assertThat(MetricsMap.familyMember(out.getFirst())).isEqualTo("thing.created.publish");
    }

    @Test
    void twoAreNotAFamilyNorAreOtherPackagesOrEvents() {
        List<MetricsMap.Gap> out = MetricsMap.families(List.of(
                gap("thing.created.publish", "failed", "src/a/Handlers.java"),
                gap("thing.updated.publish", "failed", "src/a/Handlers.java"),
                gap("thing.deleted.publish", "failed", "src/b/Handlers.java"),
                gap("thing.moved.publish", "skipped", "src/a/Handlers.java")));
        assertThat(out).extracting(MetricsMap.Gap::kind).containsOnly("");
    }
}
