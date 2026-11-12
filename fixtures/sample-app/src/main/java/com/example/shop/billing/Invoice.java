package com.example.shop.billing;

public record Invoice(String id, String orderId, boolean sent) {
    public Invoice markSent() {
        return new Invoice(id, orderId, true);
    }
}
