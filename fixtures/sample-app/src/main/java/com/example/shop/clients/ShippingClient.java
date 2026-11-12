package com.example.shop.clients;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ShippingClient {
    private final RestClient client;

    public ShippingClient() {
        this.client = RestClient.builder() // expect: HTTP_CLIENT_UNTRACED, HTTP_CLIENT_NO_TIMEOUT
                .baseUrl("http://shipping-service")
                .build();
    }

    public String status(String shipmentId) {
        return client.get().uri("/shipments/{id}", shipmentId).retrieve().body(String.class);
    }
}
