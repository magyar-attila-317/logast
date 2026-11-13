package com.example.shop.clients;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/** WebClient built by hand; the service has spring-webflux but not Boot's webclient module. */
@Component
public class PricingClient {
    private final WebClient client;

    public PricingClient() {
        this.client = WebClient.builder().baseUrl("http://pricing").build(); // expect: HTTP_CLIENT_UNTRACED
    }

    public String price(String sku) {
        return client.get().uri("/prices/{sku}", sku).retrieve().bodyToMono(String.class).block();
    }
}
