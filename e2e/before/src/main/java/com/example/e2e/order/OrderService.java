package com.example.e2e.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** The order travels as an object: its id is order.id(). */
@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private final PaymentClient payments;

    public OrderService(PaymentClient payments) {
        this.payments = payments;
    }

    public void place(Order order) {
        log.info("Placing order {}", order.id());
        payments.charge(order);
        log.info("Order {} placed", order.id());
    }

    /** Logs a quantity (items) and an id-like number (batchNumber): only the quantity is an amount to count. */
    public void ship(java.util.List<Order> orders, int batchNumber) {
        orders.forEach(payments::charge);
        log.info("Orders shipped [items={}, batchNumber={}]", orders.size(), batchNumber);
    }
}
