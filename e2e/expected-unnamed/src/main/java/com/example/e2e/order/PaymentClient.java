package com.example.e2e.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PaymentClient {
    private static final Logger log = LoggerFactory.getLogger(PaymentClient.class);

    public void charge(Order order) {
        try {
            log.debug("Charging order {} with {} cents", order.id(), order.amountCents());
            gateway(order);
        } catch (IllegalStateException e) {
            throw new PaymentException("charge failed", e);
        }
    }

    private void gateway(Order order) {
        if (order.amountCents() <= 0) {
            throw new IllegalStateException("nothing to charge");
        }
    }
}
