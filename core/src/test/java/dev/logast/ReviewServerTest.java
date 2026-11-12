package dev.logast;

import dev.logast.review.ReviewServer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewServerTest {
    @Test
    void servesPageAndOrdersOnlyWithToken() throws Exception {
        ReviewServer server = new ReviewServer(Fixtures.root(),
                () -> new IncrementalScanner(Fixtures.classpath(), r -> Fixtures.policy()), Fixtures.policy());
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
