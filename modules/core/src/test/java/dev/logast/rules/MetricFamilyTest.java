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
    void twoAreNotAFamilyNorAreOtherEvents() {
        List<MetricsMap.Gap> out = MetricsMap.families(List.of(
                gap("thing.created.publish", "failed", "src/a/Handlers.java"),
                gap("thing.updated.publish", "failed", "src/a/Handlers.java"),
                gap("thing.moved.publish", "skipped", "src/a/Handlers.java")));
        assertThat(out).extracting(MetricsMap.Gap::kind).containsOnly("");
    }

    /** #324 M2: one service per entity, each in its own package, the same broadcast failing: one family, a kind each. */
    @Test
    void oneShapeAcrossPackagesIsOneFamily() {
        List<MetricsMap.Gap> out = MetricsMap.families(List.of(
                gap("broadcast.modifier.group.created", "failed", "src/modifier/ModifierGroupService.java"),
                gap("broadcast.preset.updated", "failed", "src/preset/PresetService.java"),
                gap("broadcast.table.deleted", "failed", "src/table/TableService.java"),
                gap("order.shipped", "failed", "src/order/Orders.java")));
        assertThat(out).extracting(MetricsMap.Gap::name).containsExactly("broadcast", "broadcast", "broadcast", "order.shipped");
        assertThat(out).extracting(MetricsMap.Gap::kind).containsExactly("modifier.group.created", "preset.updated", "table.deleted", "");
    }

    /** #324 M3: names a dashboard can use, not sentences. */
    @Test
    void namesAreSubjectsNotSentences() {
        assertThat(MetricsMap.metricName("Broadcast the fire undone notice of fire to unit", "FireService")).isEqualTo("broadcast");
        assertThat(MetricsMap.metricName("GRN line qtyBaseUnits is zero cost per base unit is undefined", "GrnImport"))
                .isEqualTo("grn.line.qty.base");
        assertThat(MetricsMap.metricName("Job skipped this tick the lock query", "CleanupJob")).isEqualTo("job.skipped");
        assertThat(MetricsMap.metricName("CustomerEventPublisher", "CustomerEventPublisher")).isEqualTo("customer.event.publisher");
        assertThat(MetricsMap.metricName("GoodsReceiptCreated broadcast", "GoodsReceiptService")).isEqualTo("goods.receipt.created.broadcast");
        assertThat(MetricsMap.metricName("line", "GrnImportService")).isEqualTo("grn.import.line");
        assertThat(MetricsMap.metricName("Order items", "OrderService")).isEqualTo("order.items");
        assertThat(MetricsMap.metricName("Audit", "AuditService")).as("a word that names a thing stays alone").isEqualTo("audit");
    }
}
