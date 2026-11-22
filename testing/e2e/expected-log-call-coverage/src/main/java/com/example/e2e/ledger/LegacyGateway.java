package com.example.e2e.ledger;

import java.util.logging.Logger;

class LegacyGateway {
    private static final Logger log = Logger.getLogger(LegacyGateway.class.getName());

    void refund(String gatewayId) {
        log.info("Gateway refund requested");
    }
}
