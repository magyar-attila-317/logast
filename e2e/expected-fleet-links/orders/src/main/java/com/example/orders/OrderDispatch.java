package com.example.orders;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
class OrderDispatch {
    private static final Logger log = LoggerFactory.getLogger(OrderDispatch.class);
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MeterRegistry registry;
    private final String placedTopic;

    OrderDispatch(KafkaTemplate<String, String> kafkaTemplate, MeterRegistry registry,
                  @Value("${app.topics.placed}") String placedTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.registry = registry;
        this.placedTopic = placedTopic;
    }

    void place(String orderId) {
        kafkaTemplate.send(placedTopic, orderId);
    }

    void ship(String orderId) {
        log.info("Order shipped [orderId={}]", orderId);
        registry.counter("orders.shipped").increment();
    }

    void invoice(String orderId) {
        log.info("Invoice sent [orderId={}]", orderId);
        registry.counter("invoices.sent").increment();
    }
}
