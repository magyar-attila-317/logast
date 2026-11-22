package org.apache.hc.client5.http.impl.classic;

/** Stand-in for Apache HttpClient 5, which the e2e classpath lacks: logast sees it only by its import. */
public final class HttpClients {
    private HttpClients() {
    }

    public static CloseableHttpClient createDefault() {
        return new CloseableHttpClient();
    }

    public static HttpClientBuilder custom() {
        return new HttpClientBuilder();
    }
}
