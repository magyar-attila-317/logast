package org.apache.hc.client5.http.impl.classic;

import org.apache.hc.client5.http.config.RequestConfig;

/** Stand-in for Apache HttpClient 5 (see HttpClients). */
public class HttpClientBuilder {
    public HttpClientBuilder setDefaultRequestConfig(RequestConfig config) {
        return this;
    }

    public CloseableHttpClient build() {
        return new CloseableHttpClient();
    }
}
