package com.example.billing;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ReceiptController {
    @GetMapping("/receipts/{receiptId}")
    String receipt(@PathVariable long receiptId) {
        return "receipt";
    }
}
