package com.example.e2e.carrier;

import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;

@Component
public class CarrierClient {
    @Retryable(maxRetries = 2)
    public String track(String parcelId) {
        return "in transit";
    }

    @ConcurrencyLimit(4)
    public String quote(String parcelId) {
        return "4.20";
    }
}
