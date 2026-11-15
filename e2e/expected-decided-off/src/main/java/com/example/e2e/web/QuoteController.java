package com.example.e2e.web;

import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Two parallel lookups joined on one line (#43: two findings of one rule on one line once crashed the scan). */
@Slf4j
@RestController
public class QuoteController {
    record Quote(String price, String stock) {
    }

    @GetMapping("/quotes/{productId}")
    public Quote quote(@PathVariable String productId) {
        CompletableFuture<String> price = CompletableFuture.supplyAsync(() -> "price " + productId);
        CompletableFuture<String> stock = CompletableFuture.supplyAsync(() -> "stock " + productId);
        return new Quote(price.join(), stock.join());
    }

    /** A local named like the parameters of a generated whenComplete (#44: the fix once stopped compiling). */
    @GetMapping("/quotes/{productId}/refresh")
    public String refresh(@PathVariable String productId) {
        String result = productId.strip();
        CompletableFuture.runAsync(() -> System.out.println(result))
                .whenComplete((result2, error) -> {
                    if (error != null) {
                        log.error("Coupon sweep failed", error);
                    }
                });
        return result;
    }
}
