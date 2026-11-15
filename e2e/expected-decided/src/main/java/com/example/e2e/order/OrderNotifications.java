package com.example.e2e.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Reached only through the event: it runs inside place(), so the orderId MDC around place() is there too. */
@Component
public class OrderNotifications {
    private static final Logger log = LoggerFactory.getLogger(OrderNotifications.class);

    @EventListener
    public void onPlaced(OrderPlaced event) {
        log.info("Order confirmation sent");
        OrderMetrics.orderConfirmationSent();
        log.debug("Confirmation amount [amountCents={}]", event.amountCents());
    }
}
