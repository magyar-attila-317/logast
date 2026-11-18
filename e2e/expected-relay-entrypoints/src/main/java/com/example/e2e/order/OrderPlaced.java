package com.example.e2e.order;

/** Published by OrderService.place: Spring hands it to the listeners on the same thread. */
public record OrderPlaced(String orderId, long amountCents) {
}
