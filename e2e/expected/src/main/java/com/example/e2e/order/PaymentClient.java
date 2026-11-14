package com.example.e2e.order;

import com.example.e2e.logging.MdcScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PaymentClient {
    private static final Logger log = LoggerFactory.getLogger(PaymentClient.class);

    public void charge(Order order) {
        try (var mdc = MdcScope.of("orderId", order.id())) {
            try {
                log.debug("Charging order {} with {} cents", order.id(), order.amountCents());
                gateway(order);
            } catch (IllegalStateException e) {
                throw new PaymentException("charge failed", e);
            }
        }
    }

    private void gateway(Order order) {
        if (order.amountCents() <= 0) {
            throw new IllegalStateException("nothing to charge");
        }
    }
}
