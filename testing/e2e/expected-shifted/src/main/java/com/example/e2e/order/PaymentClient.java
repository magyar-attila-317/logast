package com.example.e2e.order;

import com.example.e2e.logging.MdcScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PaymentClient {
    private static final Logger log = LoggerFactory.getLogger(PaymentClient.class);

    public void charge(Order order) {
        try {
            try (var mdc = MdcScope.of("orderId", order.id())) {
                log.debug("Charge requested");
                log.debug("Charging the card [amountCents={}]", order.amountCents());
                gateway(order);
            }
        } catch (IllegalStateException e) {
            log.error("Charge failed [orderId={}]", order.id(), e);
            OrderMetrics.chargeFailed(); // logast:metric review, confidence: medium
            throw new PaymentException("charge failed", e);
        }
    }

    /** Rethrows without the cause: the constructor that takes it exists, so the cause can be passed on. */
    public void refund(Order order) {
        try {
            gateway(order);
        } catch (IllegalStateException e) {
            throw new PaymentException("refund failed: " + e.getMessage(), e);
        }
    }

    /** Rethrows without the cause, and OrderRejected has no constructor for one: reported. */
    public void verify(Order order) {
        try {
            gateway(order);
        } catch (IllegalStateException e) {
            throw new OrderRejected(e.getMessage());
        }
    }

    private void gateway(Order order) {
        if (order.amountCents() <= 0) {
            throw new IllegalStateException("nothing to charge");
        }
    }
}
