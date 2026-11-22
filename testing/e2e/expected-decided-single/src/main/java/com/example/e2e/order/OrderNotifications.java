package com.example.e2e.order;

import com.example.e2e.logging.ObservedContext;
import com.example.e2e.metrics.BusinessMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Reached only through the event: it runs inside place(), so the orderId MDC around place() is there too. */
@Component
public class OrderNotifications {
    private static final Logger log = LoggerFactory.getLogger(OrderNotifications.class);

    @EventListener
    public void onPlaced(OrderPlaced event) {
        log.info("Order confirmation sent");
        BusinessMetrics.orderConfirmationSent();
        log.debug("Confirmation amount [amountCents={}]", event.amountCents());
    }

    /** Runs when the transaction commits, after place() may have left its scopes: its own flow, its own ids. */
    @TransactionalEventListener
    public void onCommitted(OrderPlaced event) {
        ObservedContext.entrypoint(BusinessMetrics.ORDER_NOTIFICATIONS)
                .mdc("orderId", event.orderId())
                .quiet()
                .run(() -> {
                    log.info("Order audit recorded");
                    log.info("Pledge sweep completed");
                });
    }
}
