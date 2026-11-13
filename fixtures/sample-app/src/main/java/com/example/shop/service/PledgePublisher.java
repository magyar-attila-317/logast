package com.example.shop.service;

import com.example.shop.domain.Order;
import com.example.shop.support.LogHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.List;

/** Log argument and wording cases. */
@Service
public class PledgePublisher {
    private static final Logger log = LoggerFactory.getLogger(PledgePublisher.class);
    private static final List<String> CHANNELS = List.of("web", "pos");

    public void publish(List<String> pledgeIds, Order order) {
        log.info("Pledge batch received [pledgeIds={}]", pledgeIds); // expect: LOG_COLLECTION_ARGUMENT, LOG_KEY_STYLE
        log.info("Order pledges attached [order={}]", order); // expect: LOG_OBJECT_ARGUMENT, METRIC_OUTCOME_UNMEASURED
        log.debug("Publishing pledges"); // expect: MESSAGE_VAGUE
        log.info("No Order pledges found, won't publish"); // expect: MESSAGE_VAGUE
        log.info("Pledge channels checked [channels={}]", CHANNELS); // expect: METRIC_OUTCOME_UNMEASURED
        log.warn("ALERT: pledge feed stalled for order " + order.id());
        LogHelper.logCollection(log, "Pledges prepared", pledgeIds); // expect: LOG_HELPER
    }

    public void reserve(String orderId) {
        try (var ignored = MDC.putCloseable("order.id", orderId)) {
            log.atInfo().addKeyValue("order.id", orderId).log("Pledge reserved"); // expect: LOG_KEY_VALUE_IN_MDC, METRIC_OUTCOME_UNMEASURED
        }
    }

    public void reset() {
        MDC.clear(); // expect: MDC_CLEAR
    }
}
