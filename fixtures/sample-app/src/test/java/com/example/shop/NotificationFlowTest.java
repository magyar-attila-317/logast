package com.example.shop;

import com.example.shop.domain.Order;
import com.example.shop.notification.Mailer;
import com.example.shop.notification.NotificationService;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;

/** An ordinary existing test: logast verify observes what it logs (trace and order.id get lost on the pool thread). */
class NotificationFlowTest {
    @Test
    void confirmationIsSent() throws Exception {
        MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.put("order.id", "o-1");
        try {
            new NotificationService(new Mailer()).sendConfirmation(new Order("o-1", "c-1", null, List.of()));
            Thread.sleep(300);
        } finally {
            MDC.clear();
        }
    }
}
