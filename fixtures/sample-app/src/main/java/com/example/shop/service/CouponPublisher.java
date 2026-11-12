package com.example.shop.service;

import com.example.shop.domain.Order;
import com.example.shop.support.LogHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.List;

/** Log argument and wording cases. */
@Service
public class CouponPublisher {
    private static final Logger log = LoggerFactory.getLogger(CouponPublisher.class);
    private static final List<String> CHANNELS = List.of("web", "pos");

    public void publish(List<String> couponIds, Order order) {
        log.info("Coupon batch received [couponIds={}]", couponIds); // expect: LOG_COLLECTION_ARGUMENT, LOG_KEY_STYLE
        log.info("Order coupons attached [order={}]", order); // expect: LOG_OBJECT_ARGUMENT
        log.debug("Publishing coupons"); // expect: MESSAGE_VAGUE
        log.info("No Order coupons found, won't publish"); // expect: MESSAGE_VAGUE
        log.info("Coupon channels checked [channels={}]", CHANNELS);
        log.warn("ALERT: coupon feed stalled for order " + order.id());
        LogHelper.logCollection(log, "Coupons prepared", couponIds); // expect: LOG_HELPER
    }

    public void reserve(String orderId) {
        try (var ignored = MDC.putCloseable("order.id", orderId)) {
            log.atInfo().addKeyValue("order.id", orderId).log("Coupon reserved"); // expect: LOG_KEY_VALUE_IN_MDC
        }
    }

    public void reset() {
        MDC.clear(); // expect: MDC_CLEAR
    }
}
