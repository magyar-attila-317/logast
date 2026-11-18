package com.example.e2e.rate;

import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;

@Service
public class RateLedger {
    private final RateClient client;

    public RateLedger(RateClient client) {
        this.client = client;
    }

    @Retryable(maxRetriesString = "${resilience.retry.defaults.max-retries}", delayString = "${resilience.retry.defaults.delay}")
    public String record(String code) {
        return client.margin(code);
    }
}
