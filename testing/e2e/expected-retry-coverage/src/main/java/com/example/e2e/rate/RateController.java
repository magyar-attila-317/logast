package com.example.e2e.rate;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RateController {
    private final RateClient client;
    private final RateLedger ledger;
    private final RateArchive archive;
    private final RateSpread spread;

    public RateController(RateClient client, RateLedger ledger, RateArchive archive, RateSpread spread) {
        this.archive = archive;
        this.spread = spread;
        this.client = client;
        this.ledger = ledger;
    }

    @GetMapping("/rates/{code}/history")
    public String history(@PathVariable String code) {
        return client.history(code);
    }

    @GetMapping("/rates/{code}/margin")
    public String margin(@PathVariable String code) {
        return ledger.record(code);
    }

    @PostMapping("/rates/{code}/quotes")
    public void quote(@PathVariable String code) {
        client.submit(code);
    }

    @GetMapping("/rates/{code}/archive")
    public String archive(@PathVariable String code) {
        return archive.archived(code) + spread.spread(code);
    }
}
