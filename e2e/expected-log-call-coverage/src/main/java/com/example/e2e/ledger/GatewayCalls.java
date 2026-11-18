package com.example.e2e.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class GatewayCalls {
    private static final Logger log = LoggerFactory.getLogger(GatewayCalls.class);

    void charge(String gatewayId, String reply) {
        log.warn("Payment gateway unavailable [gatewayId={}]", gatewayId);
        log.debug("{}", reply);
        log.atDebug().addKeyValue("gatewayId", gatewayId).log("Gateway reply received");
        log.atDebug().addKeyValue("gatewayId", gatewayId).log();
        log.info("Gateway {} charged", gatewayId); // logast:ignore LOG_FORMAT METRIC_OUTCOME_UNMEASURED
    }

    void store(String gatewayRef) {
        log.atInfo().addKeyValue("gatewayRef", gatewayRef).log("Gateway reply stored");
    }
}
