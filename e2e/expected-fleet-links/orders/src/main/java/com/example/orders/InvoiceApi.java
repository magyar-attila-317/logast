package com.example.orders;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "invoice-api")
interface InvoiceApi {
    @PostMapping("/invoices")
    void create(@RequestBody String orderId);
}
