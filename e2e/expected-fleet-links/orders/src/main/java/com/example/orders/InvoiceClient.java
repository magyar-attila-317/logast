package com.example.orders;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class InvoiceClient {
    private final RestClient restClient = RestClient.create();

    void request(String orderId) {
        restClient.post().uri("http://billing/invoices").body(orderId).retrieve().toBodilessEntity();
    }
}
