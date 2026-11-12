package com.example.shop.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class InboxProcessor {
    private static final Logger log = LoggerFactory.getLogger(InboxProcessor.class);

    @InboxHandler
    public void handle(ShipmentEvent event) { // expect: ENTRYPOINT_NOT_OBSERVED, MISSING_DOMAIN_MDC, JOB_OUTCOME_MISSING
        log.info("Inbox message handled [shipmentId={}]", event.shipmentId()); // expect: LOG_KEY_STYLE
    }
}
