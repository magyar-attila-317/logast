package com.example.e2e.messaging;

import com.example.e2e.logging.MdcScope;
import com.example.e2e.logging.ObservedContext;
import com.example.e2e.logging.Outcomes;
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
        ObservedContext.entrypoint("pledge-messages.on-batch")
                .debugOnSuccess()
                .run(() -> {
                    log.info("Pledge batch received [count={}]", pledgeIds.size());
                });
    }

    @RabbitListener(queues = "pledge-returns")
    public void onReturn(String pledgeId) {
        try (var mdc = MdcScope.of("pledgeId", pledgeId)) {
            log.info("Pledge return received");
            log.info("Pledge messages completed"); // logast:wording; logast: METRIC_OUTCOME_UNMEASURED OUM-21d0c8
            Outcomes.count("pledge.messages", "completed");
        }
    }

    @JmsListener(destination = "pledge-audits")
    public void onAudit(String pledgeId) {
        try (var mdc = MdcScope.of("pledgeId", pledgeId)) {
            log.info("Pledge audit received");
            log.info("Pledge messages completed"); // logast:wording; logast: METRIC_OUTCOME_UNMEASURED OUM-620a15
            Outcomes.count("pledge.messages", "completed");
        }
    }
}
