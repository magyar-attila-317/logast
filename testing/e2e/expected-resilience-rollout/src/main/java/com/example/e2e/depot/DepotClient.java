package com.example.e2e.depot;

import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class DepotClient {
    private final WebClient depots;

    public DepotClient(ClientHttpConnector connector, WebClient.Builder webClientBuilder) {
        this.depots = webClientBuilder.clientConnector(connector).baseUrl("http://depots").build();
    }

    public String slot(String depotId) {
        WebClient slots = WebClient.create("http://slots");
        return slots.get().uri("/slots/{id}", depotId).retrieve().bodyToMono(String.class).block();
    }

    public String depot(String depotId) {
        return depots.get().uri("/depots/{id}", depotId).retrieve().bodyToMono(String.class).block();
    }
}
