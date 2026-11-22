package com.example.billing;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class InvoiceController {
    private static final Logger log = LoggerFactory.getLogger(InvoiceController.class);
    private final MeterRegistry registry;

    InvoiceController(MeterRegistry registry) {
        this.registry = registry;
    }

    @PostMapping("/invoices")
    void create(@RequestBody String orderId) {
        log.info("Invoice sent [orderId={}]", orderId);
        registry.counter("invoices.sent").increment();
    }
}
