package com.example.orders;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class ReceiptClient {
    private final RestClient restClient;

    ReceiptClient(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    String receipt(long id) {
        return restClient.get().uri("/receipts/{id}", id).retrieve().body(String.class);
    }
}
