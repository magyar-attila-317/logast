package com.example.e2e.shipment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ShipmentLog {
    private static final Logger log = LoggerFactory.getLogger(ShipmentLog.class);

    public void shipped(String order, String shipmentId) {
        log.info("Shipment sent [order={}]", order);
        log.atInfo().addKeyValue("order.id", shipmentId).log("Shipment confirmed");
    }
}
