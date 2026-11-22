package com.example.shop.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
public class CardGateway implements PaymentGateway {
    private static final Logger log = LoggerFactory.getLogger(CardGateway.class);

    @Override
    public void charge(String orderId, long amountCents) {
        log.debug("Charging {} cents for order {}", amountCents, orderId); // expect: LOG_FORMAT, AREA_MDC_MISSING
    }

    @Override
    public void refund(String orderId) {
        log.debug("Refunding order {}", orderId); // expect: LOG_FORMAT
    }
}
