package com.example.e2e.stock;

import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class StockWriter {
    @Retryable(maxRetries = 2)
    @Transactional
    public int reserve(String itemId, int quantity) {
        return quantity;
    }

    @Retryable(maxRetries = 2)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int release(String itemId) {
        return 0;
    }

    @Retryable(maxRetries = 2)
    @Transactional
    public int restock(String itemId) {
        return 1;
    }
}
