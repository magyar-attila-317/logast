package com.example.shop.billing;

public record Invoice(String id, String orderId, boolean sent) { // expect: DATA_CHANGES_UNAUDITED
    public Invoice markSent() {
        return new Invoice(id, orderId, true);
    }
}
