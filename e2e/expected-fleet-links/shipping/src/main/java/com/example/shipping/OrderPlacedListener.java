package com.example.shipping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class OrderPlacedListener {
    private static final Logger log = LoggerFactory.getLogger(OrderPlacedListener.class);

    @KafkaListener(topics = "${shipping.topics.placed}")
    void onPlaced(String orderId) {
        log.info("Shipment planned [orderId={}]", orderId);
    }
}
