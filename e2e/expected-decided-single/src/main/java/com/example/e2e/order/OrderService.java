package com.example.e2e.order;

import com.example.e2e.metrics.BusinessMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/** The order travels as an object: its id is order.id(). */
@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private final PaymentClient payments;
    private final ApplicationEventPublisher events;

    public OrderService(PaymentClient payments, ApplicationEventPublisher events) {
        this.payments = payments;
        this.events = events;
    }

    public void place(Order order) {
        log.info("Placing order");
        payments.charge(order);
        events.publishEvent(new OrderPlaced(order.id(), order.amountCents()));   // the listener runs here, same thread
        log.info("Order placed");
        BusinessMetrics.orderPlaced();
    }

    /** Logs a quantity (items) and an id-like number (batchNumber): only the quantity is an amount to count. */
    public void ship(java.util.List<Order> orders, int batchNumber) {
        orders.forEach(payments::charge);
        log.info("Orders shipped [items={}, batchNumber={}]", orders.size(), batchNumber);
        BusinessMetrics.ordersShipped(orders.size());
    }
}
