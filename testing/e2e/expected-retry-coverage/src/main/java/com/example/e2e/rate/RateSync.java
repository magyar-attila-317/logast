package com.example.e2e.rate;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
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
        log.info("Rate sync started"); // logast:wording
        System.out.println(client.current("EUR") + feed.latest("EUR"));
        log.info("Rate sync completed"); // logast:wording
    }
}
