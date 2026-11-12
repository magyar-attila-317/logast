package com.example.shop.domain;

public class PaymentException extends RuntimeException { // expect: UNMAPPED_EXCEPTION
    public PaymentException(String message, Throwable cause) {
        super(message, cause);
    }
}
