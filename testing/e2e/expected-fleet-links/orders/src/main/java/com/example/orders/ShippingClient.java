package com.example.orders;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class ShippingClient {
    private final RestClient restClient;

    ShippingClient(RestClient.Builder builder, @Value("${shipping.url:http://shipping:8080}") String url) {
        this.restClient = builder.baseUrl(url).build();
    }

    String status(long id) {
        return restClient.get().uri("/shipments/{id}", id).retrieve().body(String.class);
    }
}
