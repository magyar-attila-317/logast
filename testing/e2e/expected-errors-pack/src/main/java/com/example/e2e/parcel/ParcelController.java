package com.example.e2e.parcel;

import java.io.IOException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ParcelController {
    private final ParcelClient client;
    private final ParcelLedger ledger;

    ParcelController(ParcelClient client, ParcelLedger ledger) {
        this.client = client;
        this.ledger = ledger;
    }

    @GetMapping("/parcels/{parcelCode}")
    String track(@PathVariable String parcelCode) {
        return client.track(parcelCode);
    }

    @GetMapping("/parcels/{parcelCode}/carrier")
    String carrier(@PathVariable String parcelCode) {
        return client.carrier(parcelCode);
    }

    @PostMapping("/parcels/{parcelCode}/archive")
    void archive(@PathVariable String parcelCode) throws IOException {
        ledger.archive(parcelCode);
        ledger.restore(parcelCode);
    }
}
