package com.example.e2e.rate;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RateController {
    private final RateClient client;
    private final RateLedger ledger;

    public RateController(RateClient client, RateLedger ledger) {
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
}
