package com.example.orders;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
class LedgerClient {
    private final RestTemplate restTemplate;
    private final String ledgerUrl;

    LedgerClient(RestTemplateBuilder builder, @Value("${ledger.url}") String ledgerUrl) {
        this.restTemplate = builder.build();
        this.ledgerUrl = ledgerUrl;
    }

    String entry(long id) {
        return restTemplate.getForObject(ledgerUrl + "/entries/" + id, String.class);
    }
}
