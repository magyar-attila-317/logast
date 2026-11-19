package com.example.orders;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class ShippingClient {
    private final RestClient restClient;

    ShippingClient(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    String status(long id) {
        return restClient.get().uri("/shipments/{id}", id).retrieve().body(String.class);
    }
}
