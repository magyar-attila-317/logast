package com.example.e2e.order;

public record Order(String id, String customerId, long amountCents) {
}
