package org.apache.hc.client5.http.config;

import java.util.concurrent.TimeUnit;

/** Stand-in for Apache HttpClient 5 (see HttpClients). */
public class RequestConfig {
    public static Builder custom() {
        return new Builder();
    }

    public static class Builder {
        public Builder setResponseTimeout(long timeout, TimeUnit unit) {
            return this;
        }

        public RequestConfig build() {
            return new RequestConfig();
        }
    }
}
