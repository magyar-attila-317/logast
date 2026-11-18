package com.example.e2e.rate;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RateSpread {
    private final RestClient rest = RestClient.create("http://rates");

    public String spread(String code) {
        return fetch(code);
    }

    private String fetch(String code) {
        return rest.get().uri("/rates/{code}/spread", code).retrieve().body(String.class);
    }
}
