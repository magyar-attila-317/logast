package com.example.e2e.messaging;

import com.example.e2e.logging.ObservedContext;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Listeners whose observation differs: Kafka batches never, RabbitMQ only with a property, JMS always (Boot). */
@Component
public class CouponMessages {
    private static final Logger log = LoggerFactory.getLogger(CouponMessages.class);

    @KafkaListener(topics = "coupon-batches", batch = "true")
    public void onBatch(List<String> couponIds) {
        ObservedContext.entrypoint("coupon-messages.on-batch")
                .debugOnSuccess()
                .run(() -> {
                    log.info("Coupon batch received [count={}]", couponIds.size());
                });
    }

    @RabbitListener(queues = "coupon-returns")
    public void onReturn(String couponId) {
        log.info("Coupon return received [couponId={}]", couponId);
    }

    @JmsListener(destination = "coupon-audits")
    public void onAudit(String couponId) {
        log.info("Coupon audit received [couponId={}]", couponId);
    }
}
