package com.example.shop.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ShipmentListener {
    private static final Logger log = LoggerFactory.getLogger(ShipmentListener.class);

    @EventListener
    public void onShipped(ShipmentEvent event) {
        try (var ignored = MDC.putCloseable("order.id", event.orderId())) {
            log.info("Shipment {} dispatched for order {}", event.shipmentId(), event.orderId()); // expect: LOG_FORMAT
        }
    }
}
