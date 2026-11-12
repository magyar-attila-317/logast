package com.example.shop.payment;

import com.example.shop.domain.Order;
import com.example.shop.domain.PaymentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PaymentClient {
    private static final Logger log = LoggerFactory.getLogger(PaymentClient.class);

    private final PaymentGateway gateway;

    public PaymentClient(PaymentGateway gateway) {
        this.gateway = gateway;
    }

    public void charge(Order order, long amountCents) {
        try {
            gateway.charge(order.id(), amountCents);
        } catch (IllegalStateException e) {
            log.error("Payment charge failed for order {}", order.id(), e); // expect: LOG_AND_RETHROW, LOG_FORMAT, AREA_MDC_MISSING
            throw new PaymentException("charge failed", e); // expect: UNMAPPED_EXCEPTION
        }
    }

    public void refund(String orderId) {
        try {
            gateway.refund(orderId);
        } catch (IllegalStateException e) {
            log.warn("Refund failed for order {}", orderId); // expect: LOST_THROWABLE, LOG_FORMAT
        }
    }
}
