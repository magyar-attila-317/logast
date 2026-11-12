package com.example.shop.service;

import com.example.shop.domain.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** REPEATED_LOG_VALUE cases: an identifier in every message of a method belongs in MDC once. */
@Service
public class RefundService {
    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    public void refund(String orderId, long amountCents) {
        log.info("Refund started [order.id={}]", orderId); // expect: REPEATED_LOG_VALUE, LOG_FORMAT
        check(amountCents);
        log.info("Refund completed [order.id={}, amount.cents={}]", orderId, amountCents); // expect: LOG_FORMAT
    }

    public void reverse(Order order) {
        log.info("Reversal started [order.id={}]", order.id()); // expect: REPEATED_LOG_VALUE, LOG_FORMAT
        log.info("Reversal completed [order.id={}]", order.id()); // expect: LOG_FORMAT
    }

    public void retry(String batch) {
        log.info("Retry batch started");
        String orderId = lookup(batch);
        log.info("Retrying refund [order.id={}]", orderId); // expect: REPEATED_LOG_VALUE, LOG_FORMAT, MESSAGE_PHRASING
        log.warn("Refund retry scheduled [order.id={}]", orderId); // expect: LOG_FORMAT
    }

    public void normalise(String orderId) {
        log.info("Normalising [order.id={}]", orderId);
        orderId = orderId.trim();
        log.info("Normalised [order.id={}]", orderId);
    }

    public void audit(Order order) {
        if (order == null) {
            log.warn("Audit skipped");
            return;
        }
        log.info("Audit started [order.id={}]", order.id());
        log.info("Audit completed [order.id={}]", order.id());
    }

    private void check(long amountCents) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }

    private String lookup(String batch) {
        return batch.substring(0, Math.min(8, batch.length()));
    }
}
