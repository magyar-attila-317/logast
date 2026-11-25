package com.example.e2e.order;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** A remote service: each call waits for the network. */
@Component
public class LoyaltyClient {
    private final RestClient rest;

    public LoyaltyClient(RestClient.Builder builder) {
        this.rest = builder.baseUrl("http://loyalty").build();
    }

    /** Any failure becomes "0 points": the caller cannot tell it from an empty balance. */
    public long balance(String customerId) {
        try {
            Long points = rest.get().uri("/balances/{customerId}", customerId).retrieve().body(Long.class);
            return points == null ? 0L : points;
        } catch (Exception e) {
            return 0L;
        }
    }

    public void award(String orderId, long points) {
        rest.post().uri("/awards/{orderId}?points={points}", orderId, points).retrieve().toBodilessEntity();
    }
}
