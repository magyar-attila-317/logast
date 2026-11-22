package com.example.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class OrderPlacedAudit {
    private static final Logger log = LoggerFactory.getLogger(OrderPlacedAudit.class);

    @KafkaListener(topics = "orders.placed", groupId = "billing")
    void onPlaced(String orderId) {
        log.info("Order placement noted [orderId={}]", orderId);
    }
}
