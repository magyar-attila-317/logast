package com.example.e2e.depot;

import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.stereotype.Component;

@Component
public class DepotFeeds {
    private final CloseableHttpClient feeds = HttpClients.createDefault();
    private final CloseableHttpClient alerts = HttpClients.custom()
            .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(5, TimeUnit.SECONDS).build())
            .build();

    public boolean ready() {
        return feeds != null && alerts != null;
    }
}
