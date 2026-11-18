package com.example.e2e.stock;

import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class StockWriter {
    @Retryable(maxRetriesString = "${resilience.retry.defaults.max-retries}", delayString = "${resilience.retry.defaults.delay}")
    @Transactional
    public int reserve(String itemId, int quantity) {
        return quantity;
    }

    @Retryable(maxRetriesString = "${resilience.retry.defaults.max-retries}", delayString = "${resilience.retry.defaults.delay}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int release(String itemId) {
        return 0;
    }

    @Retryable(maxRetriesString = "${resilience.retry.defaults.max-retries}", delayString = "${resilience.retry.defaults.delay}")
    @Transactional
    public int restock(String itemId) {
        return 1;
    }
}
