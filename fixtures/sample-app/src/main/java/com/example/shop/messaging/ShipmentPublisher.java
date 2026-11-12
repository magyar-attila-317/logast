package com.example.shop.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class ShipmentPublisher {
    private static final Logger log = LoggerFactory.getLogger(ShipmentPublisher.class);

    private final KafkaTemplate<String, String> kafka; // expect: KAFKA_OBSERVATION_DISABLED

    public ShipmentPublisher(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    public void publish(String shipmentId) {
        kafka.send("shipments", shipmentId);
    }

    @KafkaListener(topics = "shipment-acks")
    public void onAck(String payload) { // expect: KAFKA_OBSERVATION_DISABLED, KAFKA_NO_ERROR_HANDLER, JOB_OUTCOME_MISSING
        log.info("Shipment acknowledgement received");
    }
}
