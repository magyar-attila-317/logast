package com.example.e2e.order;

import com.example.e2e.logging.MdcScope;
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
        try (var mdc = MdcScope.of("order.id", order == null ? null : order.id())) {
            if (order == null) {
                log.info("Order tombstone received");
                return;
            }
            log.info("Order event received [amount.cents={}]", order.amountCents());
        }
    }
}
