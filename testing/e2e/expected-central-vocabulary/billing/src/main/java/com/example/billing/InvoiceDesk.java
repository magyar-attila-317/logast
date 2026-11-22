package com.example.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class InvoiceDesk {
    private static final Logger log = LoggerFactory.getLogger(InvoiceDesk.class);

    void issue(Order order) {
        MDC.put("orderId", String.valueOf(order.id()));
        log.info("Invoice issued [orderId={}]", order.id());
    }
}
