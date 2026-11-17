package com.example.e2e.review;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

class PartnerLookup {
    String name(String partnerId) {
        RestClient client = RestClient.create("http://partners");
        return client.get().uri("/partners/{partnerId}", partnerId).retrieve().body(String.class);
    }

    String status(String partnerId) {
        RestTemplate template = new RestTemplate();
        return template.getForObject("http://partners/status/{partnerId}", String.class, partnerId);
    }
}
