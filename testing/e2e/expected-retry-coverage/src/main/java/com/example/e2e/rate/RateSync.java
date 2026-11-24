package com.example.e2e.rate;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RateSync {
    private final RateClient client;
    private final RateFeed feed;

    public RateSync(RateClient client, RateFeed feed) {
        this.client = client;
        this.feed = feed;
    }

    @Scheduled(fixedDelay = 60000)
    public void refresh() {
        System.out.println(client.current("EUR") + feed.latest("EUR"));
    }
}
