package dev.logast;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** #279: pattern defaults in traffic.yaml, for services with too many entrypoints to rate one by one. */
class TrafficPatternsTest {
    @TempDir
    Path tmp;

    @Test
    void anOwnValueWinsThenTheMostSpecificPattern() throws Exception {
        Path file = tmp.resolve("traffic.yaml");
        Files.writeString(file, """
                patterns:
                  "* /api/**": 50000
                  "* /api/admin/**": 10
                  "GET /api/events/*": 400
                entrypoints:
                  "POST /api/admin/reindex": 2
                  "GET /api/orders":
                """);
        Traffic t = Traffic.load(tmp, file);
        assertThat(t.perDay("POST /api/admin/reindex")).as("its own value").isEqualTo(2d);
        assertThat(t.perDay("DELETE /api/admin/users/{id}")).as("the more specific pattern").isEqualTo(10d);
        assertThat(t.perDay("GET /api/events/stream")).isEqualTo(400d);
        assertThat(t.perDay("GET /api/events/a/b")).as("* stays within a segment").isEqualTo(50000d);
        assertThat(t.perDay("GET /api/orders")).as("blank: the pattern").isEqualTo(50000d);
        assertThat(t.perDay("GET /health")).as("no match").isNull();
        assertThat(t.known()).isTrue();
    }

    @Test
    void groupsOfEndpointsWithoutARateAreOfferedAsPatterns() {
        Traffic existing = Traffic.of(java.util.Map.of("GET /api/orders/{id}", 500.0), java.util.Map.of("* /api/admin/**", 10.0), "test");
        List<String> labels = List.of("GET /api/reports/daily", "GET /api/reports/weekly", "POST /api/reports/run",
                "GET /api/orders/{id}", "POST /api/orders/new", "GET /api/orders/list",
                "GET /api/admin/a", "GET /api/admin/b", "GET /api/admin/c", "GET /health", "kafka orders",
                "POST /pledges/{id}/confirm", "GET /pledges/{id}", "DELETE /pledges/{id}", "POST /pledges/allot");
        assertThat(Traffic.suggestedPatterns(labels, existing)).as("orders has two without a rate; admin has a pattern")
                .containsExactly(java.util.Map.entry("* /pledges/**", 4), java.util.Map.entry("* /api/reports/**", 3));
    }

    @Test
    void theTemplateKeepsThePatterns() {
        Traffic existing = Traffic.of(Map.of(), Map.of("* /api/admin/**", 10d), "traffic.yaml");
        assertThat(Traffic.template(List.of(), existing)).contains("patterns:\n  \"* /api/admin/**\": 10\nentrypoints:\n");
        assertThat(Traffic.template(List.of(), Traffic.none())).contains("patterns: {}\nentrypoints:\n");
    }
}
