package com.example.e2e.order;

import com.example.e2e.logging.MdcScope;
import com.example.e2e.metrics.BusinessMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The order travels as an object: its id is order.id(). */
@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private final PaymentClient payments;
    private final ApplicationEventPublisher events;
    private final LoyaltyClient loyalty;

    public OrderService(PaymentClient payments, ApplicationEventPublisher events, LoyaltyClient loyalty) {
        this.payments = payments;
        this.events = events;
        this.loyalty = loyalty;
    }

    /** The loyalty call runs inside the transaction: the database connection waits for the network. */
    @Transactional
    public void placeAndReward(Order order) {
        try (var mdc = MdcScope.of("orderId", order.id())) {
            place(order);
            loyalty.award(order.id(), order.amountCents() / 100);
        }
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
        if (orders.isEmpty()) {
            log.info("Order shipment skipped");
            BusinessMetrics.orderShipmentSkipped();
            return;
        }
        orders.forEach(payments::charge);
        log.info("Orders shipped [items={}, batchNumber={}]", orders.size(), batchNumber);
        BusinessMetrics.ordersShipped(orders.size());
    }
}
