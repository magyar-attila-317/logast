package com.example.e2e.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** A compacted topic: a deleted order arrives as a null payload (#49: the MDC value must not throw on it). */
@Component
public class OrderEvents {
    private static final Logger log = LoggerFactory.getLogger(OrderEvents.class);

    @KafkaListener(topics = "orders")
    public void onOrder(Order order) {
        if (order == null) {
            log.info("Order tombstone received");
            return;
        }
        log.info("Order event received [amountCents={}]", order.amountCents());
    }
}
