package com.example.orders;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "ledgerClient")
interface LedgerApi {
    @GetMapping("/entries/{entryId}")
    String entry(@PathVariable long entryId);
}
