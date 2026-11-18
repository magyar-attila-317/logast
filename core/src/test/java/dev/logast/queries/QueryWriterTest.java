package dev.logast.queries;

import dev.logast.policy.Policy;
import dev.logast.queries.ServiceSignals.Flow;
import dev.logast.queries.ServiceSignals.Meter;
import dev.logast.queries.ServiceSignals.Timing;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * logast queries (#106): the names each backend stores. The e2e scenario (expected-decided) covers a real service in
 * the logstash format; the other formats, the service label and the left-out Splunk part are pinned here.
 */
class QueryWriterTest {
    private static ServiceSignals signals(String format, String label) {
        var tags = new LinkedHashMap<String, String>();
        tags.put("method", "POST");
        tags.put("uri", "/orders");
        return new ServiceSignals("shop", format, label,
                List.of(new Flow("http", "POST /orders", Timing.HTTP, "http.server.requests", tags, "A.java", 1),
                        new Flow("kafka", "@KafkaListener orders", Timing.OBSERVATION, "order-intake.on-message",
                                new LinkedHashMap<>(), "B.java", 2)),
                List.of(new Meter("order.shipment", "counter", List.of("outcome"), new TreeSet<>(List.of("skipped")), "C.java", 3),
                        new Meter("order.shipment.items", "summary", List.of("outcome"), new TreeSet<>(), "C.java", 3)),
                List.of("orderId"));
    }

    private static Policy.Queries queries(String index) {
        return new Policy.Queries(null, new Policy.Splunk(index, null), null);
    }

    @Test
    void ecsLogsUseItsFieldNames() {
        String md = QueryWriter.markdown(signals("ecs", "application"), queries("app-logs")).text();
        assertThat(md).contains("index=app-logs \"service.name\"=\"shop\" \"log.level\"=ERROR\n| stats count latest(_time) as last by "
                        + "\"log.logger\", message")
                .contains("\"error.type\"=*\n| stats count by \"error.type\", \"log.logger\"")
                .contains("index=app-logs \"service.name\"=\"shop\" orderId=\"<orderId>\"\n| sort _time\n| table _time traceId");
    }

    @Test
    void gelfPrefixesMdcAndNamesTheServiceInHost() {
        String md = QueryWriter.markdown(signals("gelf", "application"), queries("app-logs")).text();
        assertThat(md).contains("index=app-logs host=\"shop\" _level_name=ERROR")
                .contains("_traceId=\"<trace id>\"").contains("_orderId=\"<orderId>\"").contains("by _error_type, _log_logger");
    }

    @Test
    void prometheusNamesFollowMicrometer() {
        String md = QueryWriter.markdown(signals("ecs", "job"), queries("")).text();
        assertThat(md).contains("sum by (method, uri) (rate(http_server_requests_seconds_count{job=\"shop\", outcome=\"SERVER_ERROR\"}[5m]))")
                .contains("| POST /orders | `method=\"POST\", uri=\"/orders\"` |")
                .contains("sum by (error) (rate(order_intake_on_message_seconds_count{job=\"shop\", error!=\"none\"}[5m]))")
                .contains("sum by (outcome) (increase(order_shipment_total{job=\"shop\"}[1h]))")
                .contains("sum by (outcome) (rate(order_shipment_items_sum{job=\"shop\"}[1h]))")
                .contains("(the scrape job's name;");
    }

    @Test
    void nrqlQuotesNamesWithDashes() {
        String md = QueryWriter.markdown(signals("ecs", "application"), queries("")).text();
        assertThat(md).contains("FROM Metric SELECT count(`order-intake.on-message`)")
                .contains("FROM Metric SELECT sum(order.shipment) WHERE service.name = 'shop' FACET outcome TIMESERIES 1 hour");
    }

    @Test
    void withoutAnIndexSplunkIsLeftOutWithAHint() {
        QueryWriter.Result r = QueryWriter.markdown(signals("ecs", "application"), queries(""));
        assertThat(r.text()).doesNotContain("## Splunk");
        assertThat(r.hints()).containsExactly("no Splunk queries: set queries.splunk.index in logast.yaml (the index the service logs to)");
        assertThat(r.summary()).doesNotContain("SPL");
    }

    @Test
    void backendsCanBeChosen() {
        QueryWriter.Result r = QueryWriter.markdown(signals("ecs", "application"),
                new Policy.Queries(List.of("splunk"), new Policy.Splunk("app-logs", "_json"), null));
        assertThat(r.text()).doesNotContain("## Prometheus").doesNotContain("## New Relic")
                .contains("index=app-logs sourcetype=_json \"service.name\"=\"shop\"");
        assertThat(r.summary()).matches("\\d+ SPL");
    }
}
