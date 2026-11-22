package com.example.e2e.order;

/** A business rejection with a message only: no constructor that takes a cause. */
public class OrderRejected extends RuntimeException {
    public OrderRejected(String message) {
        super(message);
    }
}
