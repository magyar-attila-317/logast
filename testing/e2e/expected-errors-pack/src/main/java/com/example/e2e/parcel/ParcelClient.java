package com.example.e2e.parcel;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
class ParcelClient {
    private final RestClient rest;

    ParcelClient(RestClient.Builder builder) {
        this.rest = builder.baseUrl("http://parcels").build();
    }

    String track(String parcelCode) {
        return rest.get().uri("/parcels/{parcelCode}", parcelCode).retrieve().body(String.class);
    }

    String carrier(String parcelCode) {
        try {
            return rest.get().uri("/parcels/{parcelCode}/carrier", parcelCode).retrieve().body(String.class);
        } catch (RestClientException e) {
            throw new ParcelLate("carrier unknown for " + parcelCode);
        }
    }
}
