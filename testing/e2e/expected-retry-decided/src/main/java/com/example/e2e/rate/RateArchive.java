package com.example.e2e.rate;

import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RateArchive {
    private final RestClient rest = RestClient.create("http://rates");

    public String archived(String code) {
        return fetch(code);
    }

    @Retryable(maxRetriesString = "${resilience.retry.defaults.max-retries}", delayString = "${resilience.retry.defaults.delay}")
    private String fetch(String code) {
        return rest.get().uri("/rates/{code}/archive", code).retrieve().body(String.class);
    }
}
