package dev.logast;

import dev.logast.fleet.FleetReport;
import dev.logast.metrics.MeterCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MeterCatalogTest {
    @Test
    void listsCodeAnnotationObservationAndFrameworkMeters() {
        List<MeterCatalog.Meter> meters = MeterCatalog.of(Fixtures.analysis());
        assertThat(meters).extracting(MeterCatalog.Meter::name)
                .contains("http.server.requests", "tasks.scheduled.execution", "purchasesPlaced", "stock.reserved",
                        "stock.pending", "stock.restocked", "stock.queue");
        // a static final constant as the name, and builder-chain tags
        assertThat(meters).filteredOn(m -> m.name().equals("stock.reserved")).extracting(MeterCatalog.Meter::tags)
                .containsExactlyInAnyOrder(List.of("channel"), List.of("channel", "reason"));
        assertThat(meters).filteredOn(m -> m.name().equals("purchasesPlaced")).singleElement()
                .satisfies(m -> assertThat(m.tags()).containsExactly("orderId", "channel"))
                .satisfies(m -> assertThat(m.type()).isEqualTo("counter"));
        assertThat(meters).filteredOn(m -> m.name().equals("stock.pending")).singleElement()
                .satisfies(m -> assertThat(m.type()).isEqualTo("gauge"));
    }

    @Test
    void fleetComparesMetersAcrossServices() {
        var a = service("orders", List.of(meter("orders.placed", "counter", "channel"), meter("refund", "observation")));
        var b = service("billing", List.of(meter("orders.placed", "counter", "channel", "tenant"), meter("order_placed", "counter")));
        var c = service("kitchen", List.of(meter("refund", "observation")));
        String rows = FleetReport.meterRows(List.of(a, b, c));
        assertThat(rows).contains("orders.placed").contains("differs:");
        assertThat(rows).contains("<code>refund</code>").contains("consistent");
        assertThat(rows).contains("order_placed").contains("similar names");
        assertThat(FleetReport.normalize("orders.placed")).isEqualTo(FleetReport.normalize("order_placed"))
                .isEqualTo(FleetReport.normalize("ordersPlaced"));
    }

    private static MeterCatalog.Meter meter(String name, String type, String... tags) {
        return new MeterCatalog.Meter(name, type, List.of(tags), "X.java", 1, "code", true);
    }

    private static FleetReport.Service service(String name, List<MeterCatalog.Meter> meters) {
        return new FleetReport.Service(name, "/" + name, 10, 1, Map.of(), Map.of(), Map.of(), 100, List.of(), meters);
    }
}
