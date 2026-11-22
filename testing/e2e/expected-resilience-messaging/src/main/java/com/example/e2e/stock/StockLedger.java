package com.example.e2e.stock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockLedger {
    private final StockWriter writer;

    public StockLedger(StockWriter writer) {
        this.writer = writer;
    }

    @Transactional
    public int book(String itemId, int quantity) {
        return writer.reserve(itemId, quantity) + writer.release(itemId);
    }

    public int refill(String itemId) {
        return writer.restock(itemId);
    }
}
