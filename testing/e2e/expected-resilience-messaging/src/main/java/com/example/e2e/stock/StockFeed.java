package com.example.e2e.stock;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class StockFeed {
    private final WebClient client;

    public StockFeed(WebClient.Builder builder) {
        this.client = builder.baseUrl("http://stock").build();
    }

    public String level(String itemId) {
        return client.get().uri("/levels/{id}", itemId).retrieve().bodyToMono(String.class).block();
    }
}
