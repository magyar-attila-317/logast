package com.example.e2e.label;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
class LabelStore {
    private final WebClient client;

    LabelStore(WebClient.Builder builder) {
        this.client = builder.baseUrl("http://labels").build();
    }

    String find(String labelCode) {
        return client.get().uri("/labels/{labelCode}", labelCode).retrieve().bodyToMono(String.class).block();
    }
}
