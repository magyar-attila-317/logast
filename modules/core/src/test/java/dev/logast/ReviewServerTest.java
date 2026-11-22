package dev.logast;

import dev.logast.review.ReviewServer;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewServerTest {
    @org.junit.jupiter.api.io.TempDir
    Path tmp;

    /** #122: metrics.yaml and retry.yaml entries are listed with their default and decided through the page. */
    @Test
    void listsAndDecidesMetricsAndRetries() throws Exception {
        Path metrics = tmp.resolve(dev.logast.apply.MetricsFile.PATH);
        Files.createDirectories(metrics.getParent());
        Files.writeString(metrics, """
                # One entry per outcome.
                OUM-000001:
                  count: ""
                  name: pledge.usage
                  outcome: saved
                  _message: INFO "Pledge usage saved"
                  _where: src/main/java/x/PledgeRepository.java:33 PledgeRepository.countUse
                """);
        Path retry = tmp.resolve(dev.logast.rules.RetrySettingsRules.WORK_FILE);
        Files.writeString(retry, """
                # Optional. Every retry decision of the service.
                usages: {}
                candidates:
                  RateClient.current:    # src/main/java/x/RateClient.java:15, reached from GET /rates (request)
                    retry: ""    # yes: one quick retry (maxRetries 1, delay 100 ms) · no
                  RateClient.history:    # src/main/java/x/RateClient.java:19, reached from GET /history (request)
                    retry: ""    # yes: one quick retry (maxRetries 1, delay 100 ms) · no
                """);
        ReviewServer server = new ReviewServer(tmp, () -> new RoundScanner(java.util.List.of(), r -> Fixtures.policy()), Fixtures.policy());
        String url = server.start(0);
        try {
            HttpClient http = HttpClient.newHttpClient();
            String base = url.substring(0, url.indexOf("/?t="));
            String token = url.substring(url.indexOf("t=") + 2);
            assertThat(get(http, base + "/api/decisions").statusCode()).isEqualTo(403);
            String listed = get(http, base + "/api/decisions?t=" + token).body().replace(" ", "");
            assertThat(listed).contains("\"id\":\"OUM-000001\"", "\"name\":\"pledge.usage\"", "\"default\":\"blank:nometricisadded\"",
                    "\"id\":\"RateClient.current\"", "\"default\":\"blank:noretry\"", "reachedfromGET/rates(request)");

            String body = "{\"metrics\":{\"OUM-000001\":{\"count\":\"counter\",\"name\":\"pledge.use\"}},\"retry\":{\"RateClient.history\":\"no\"}}";
            HttpResponse<String> saved = http.send(HttpRequest.newBuilder(URI.create(base + "/api/decide?t=" + token))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(saved.body().replace(" ", "")).contains("\"saved\":2");
            // the yaml files stay the stored form: what scan / apply read back
            assertThat(dev.logast.apply.MetricsFile.read(tmp).get("OUM-000001").count()).isEqualTo("counter");
            assertThat(dev.logast.apply.MetricsFile.read(tmp).get("OUM-000001").name()).isEqualTo("pledge.use");
            assertThat(Files.readString(metrics)).startsWith("# One entry per outcome.\n").contains("_where:");
            assertThat(dev.logast.rules.RetrySettingsRules.candidateMarks(tmp)).containsEntry("RateClient.history", "no")
                    .containsEntry("RateClient.current", "");
            assertThat(Files.readString(retry)).contains("    retry: \"no\"    # yes: one quick retry");

            HttpResponse<String> bad = http.send(HttpRequest.newBuilder(URI.create(base + "/api/decide?t=" + token))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"retry\":{\"RateClient.current\":\"maybe\"}}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(bad.statusCode()).isEqualTo(400);
        } finally {
            server.stop();
        }
    }

    @Test
    void servesPageAndOrdersOnlyWithToken() throws Exception {
        ReviewServer server = new ReviewServer(Fixtures.root(),
                () -> new RoundScanner(Fixtures.classpath(), r -> Fixtures.policy()), Fixtures.policy());
        String url = server.start(0);
        try {
            HttpClient http = HttpClient.newHttpClient();
            String base = url.substring(0, url.indexOf("/?t="));
            String token = url.substring(url.indexOf("t=") + 2);
            assertThat(get(http, base + "/api/orders").statusCode()).isEqualTo(403);
            assertThat(get(http, base + "/?t=" + token).body()).contains("<title>Logast Review</title>");
            String orders = get(http, base + "/api/orders?t=" + token).body();
            assertThat(orders.replace(" ", "")).contains("\"kind\":\"text\"", "SWALLOWED_EXCEPTION", "\"pattern\":\"{subject}failed\"");
        } finally {
            server.stop();
        }
    }

    private static HttpResponse<String> get(HttpClient http, String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
