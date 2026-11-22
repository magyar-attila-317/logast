package com.example.e2e.rate;

import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

@Component
public class RateFeed {
    private final WebClient client;

    public RateFeed(WebClient.Builder builder) {
        this.client = builder.baseUrl("http://feed").build();
    }

    public String latest(String code) {
        return client.get().uri("/latest/{code}", code).retrieve().bodyToMono(String.class)
                .retryWhen(Retry.backoff(2, Duration.ofMillis(200))).block();
    }
}
