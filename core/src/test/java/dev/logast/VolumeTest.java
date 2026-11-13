package dev.logast;

import dev.logast.orders.ScanResult;
import dev.logast.orders.WorkOrder;
import dev.logast.policy.Policy;
import dev.logast.rules.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VolumeTest {
    @TempDir
    Path tmp;

    @Test
    void volumeNeedsProvidedTrafficAndUsesTheBudget() {
        Analysis a = Analysis.run(Fixtures.root(), Fixtures.classpath(), Fixtures.policy());
        assertThat(Rules.scan(a).orders()).noneMatch(o -> o.rule().equals(Rules.LOG_VOLUME)); // no data: no guess
        a.withTraffic(Traffic.of(Map.of("POST /orders", 500_000d, "@Scheduled ReconciliationJob.reconcile", 1_440d), "test"));
        ScanResult r = Rules.scan(a);
        assertThat(r.orders()).filteredOn(o -> o.rule().equals(Rules.LOG_VOLUME)).extracting(WorkOrder::method)
                .contains("OrderController.create", "DefaultOrderService.place")
                .doesNotContain("ReconciliationJob.reconcile", "OrderController.get");   // below budget / no data
        assertThat(r.orders()).filteredOn(o -> o.rule().equals(Rules.LOG_VOLUME))
                .noneMatch(o -> o.method().equals("PaymentClient.charge")); // the only log there is in a catch block
    }

    @Test
    void logInLoopIsFlaggedWithoutTraffic() {
        Policy noDowngrade = Fixtures.policy().withLevels(new Policy.Levels(null, null, false, null, null));
        ScanResult r = Rules.scan(Analysis.run(Fixtures.root(), Fixtures.classpath(), noDowngrade));
        assertThat(r.orders()).filteredOn(o -> o.rule().equals(Rules.LOG_IN_LOOP)).extracting(WorkOrder::method)
                .containsExactly("ReportExporter.export");
    }

    @Test
    void readsPrometheusQueryResults() throws Exception {
        Path f = tmp.resolve("traffic.json");
        Files.writeString(f, """
                {"status":"success","data":{"resultType":"vector","result":[
                  {"metric":{"method":"POST","uri":"/orders"},"value":[1731400000,"120000"]},
                  {"metric":{"method":"GET","uri":"/orders/{orderId}"},"value":[1731400000,"2500000.5"]}]}}
                """);
        Traffic t = Traffic.load(tmp, f);
        assertThat(t.perDay("POST /orders")).isEqualTo(120_000d);
        assertThat(t.perDay("GET /orders/{orderId}")).isEqualTo(2_500_000.5);
    }

    @Test
    void templateListsEveryEntrypointAndKeepsFilledValues() throws Exception {
        Analysis a = Fixtures.analysis();
        Path f = tmp.resolve("traffic.yaml");
        Files.writeString(f, Traffic.template(a.entrypoints, Traffic.none()));
        assertThat(Traffic.load(tmp, f).known()).isFalse();                  // empty = no data, not zero
        Files.writeString(f, Files.readString(f).replace("\"POST /orders\": ", "\"POST /orders\": 120000")
                + "  \"GET /legacy\": 5\n");
        String again = Traffic.template(a.entrypoints, Traffic.load(tmp, f));
        assertThat(again).contains("\"POST /orders\": 120000", "\"@Scheduled ReconciliationJob.reconcile\": ",
                "#  \"GET /legacy\": 5.0");
        assertThat(again.lines().filter(l -> l.startsWith("  \"")).count())
                .isEqualTo(a.entrypoints.stream().map(dev.logast.model.Entrypoint::label).distinct().count());
    }

    @Test
    void sameMethodAndPathIsOneKey() {
        var eps = Fixtures.analysis().entrypoints;
        var csv = new dev.logast.model.Entrypoint(eps.get(0).method(), dev.logast.model.Entrypoint.Kind.HTTP, "GET /report", true);
        var json = new dev.logast.model.Entrypoint(eps.get(1).method(), dev.logast.model.Entrypoint.Kind.HTTP, "GET /report", true);
        String t = Traffic.template(java.util.List.of(csv, json), Traffic.none());
        assertThat(t.lines().filter(l -> l.startsWith("  \"GET /report\"")).toList()).singleElement()
                .satisfies(l -> assertThat(l).contains(eps.get(0).method().label() + " + " + eps.get(1).method().label()));
    }
}
