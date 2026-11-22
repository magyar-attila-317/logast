package com.example.e2e.messaging;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Listeners whose observation differs: Kafka batches never, RabbitMQ only with a property, JMS always (Boot). */
@Component
public class PledgeMessages {
    private static final Logger log = LoggerFactory.getLogger(PledgeMessages.class);

    @KafkaListener(topics = "pledge-batches", batch = "true")
    public void onBatch(List<String> pledgeIds) {
        log.info("Pledge batch received [count={}]", pledgeIds.size());
    }

    @RabbitListener(queues = "pledge-returns")
    public void onReturn(String pledgeId) {
        log.info("Pledge return received [pledgeId={}]", pledgeId);
    }

    @JmsListener(destination = "pledge-audits")
    public void onAudit(String pledgeId) {
        log.info("Pledge audit received [pledgeId={}]", pledgeId);
    }
}
