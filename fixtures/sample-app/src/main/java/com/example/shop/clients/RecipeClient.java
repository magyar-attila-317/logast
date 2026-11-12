package com.example.shop.clients;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** JDK HttpClient (no traceparent) and a self-scheduled cache sweeper (no trace per run). */
@Component
public class RecipeClient {
    private static final Logger log = LoggerFactory.getLogger(RecipeClient.class);
    private final HttpClient http = HttpClient.newHttpClient(); // expect: HTTP_CLIENT_UNTRACED, HTTP_CLIENT_NO_TIMEOUT
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor();

    public RecipeClient() {
        sweeper.scheduleAtFixedRate(this::sweep, 1, 1, TimeUnit.MINUTES); // expect: SCHEDULED_TASK_UNTRACED
    }

    public String fetch(String recipeId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://recipes.example.com/" + recipeId)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private void sweep() {
        log.debug("Recipe cache sweep completed");
    }
}
