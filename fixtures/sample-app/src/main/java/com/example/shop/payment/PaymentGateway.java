package com.example.shop.payment;

public interface PaymentGateway {
    void charge(String orderId, long amountCents);

    void refund(String orderId);
}
