package com.example.shop.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!prod")
public class FakeGateway implements PaymentGateway {
    private static final Logger log = LoggerFactory.getLogger(FakeGateway.class);

    @Override
    public void charge(String orderId, long amountCents) {
        if (amountCents < 0) {
            throw new IllegalStateException("negative amount");
        }
        log.debug("Fake charge accepted amount={}", amountCents); // expect: LOG_FORMAT, AREA_MDC_MISSING
    }

    @Override
    public void refund(String orderId) {
    }
}
