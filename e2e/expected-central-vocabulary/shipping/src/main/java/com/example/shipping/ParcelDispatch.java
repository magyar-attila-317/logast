package com.example.shipping;

import dev.logast.support.MdcScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ParcelDispatch {
    private static final Logger log = LoggerFactory.getLogger(ParcelDispatch.class);

    void dispatch(Order order, String customerId) {
        try (var mdc = MdcScope.of("orderId", String.valueOf(order.id()))) {
            log.info("Parcel dispatched [customerId={}]", customerId);
        }
    }
}
