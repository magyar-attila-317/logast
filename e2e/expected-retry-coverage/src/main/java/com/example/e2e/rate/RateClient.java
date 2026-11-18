package com.example.e2e.rate;

import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RateClient {
    private final RestClient rest;

    public RateClient(RestClient.Builder builder) {
        this.rest = builder.baseUrl("http://rates").build();
    }

    public String current(String code) {
        return rest.get().uri("/rates/{code}", code).retrieve().body(String.class);
    }

    public String history(String code) {
        return rest.get().uri("/rates/{code}/history", code).retrieve().body(String.class);
    }

    @Retryable(maxRetriesString = "${resilience.retry.defaults.max-retries}", delayString = "${resilience.retry.defaults.delay}")
    public String margin(String code) {
        return rest.get().uri("/margins/{code}", code).retrieve().body(String.class);
    }

    public void submit(String code) {
        rest.post().uri("/quotes/{code}", code).retrieve().toBodilessEntity();
    }
}
