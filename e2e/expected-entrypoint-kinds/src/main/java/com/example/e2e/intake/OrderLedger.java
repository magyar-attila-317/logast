package com.example.e2e.intake;

import com.example.e2e.logging.MdcScope;
import com.example.e2e.logging.ObservedContext;
import com.example.e2e.order.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Its own flow after the commit: an MdcScope (the order's), which measures nothing. */
@Slf4j
@Component
public class OrderLedger {
    private final CouponFeed feed;

    OrderLedger(CouponFeed feed) {
        this.feed = feed;
    }

    @TransactionalEventListener
    public void onSettled(Order order) {
        try (var mdc = MdcScope.of("orderId", order.id())) {
            ObservedContext.entrypoint(IntakeMetrics.ORDER_LEDGER)
                    .quiet()
                    .run(() -> {
                        feed.load();
                        log.info("Order ledger completed"); // logast:wording
                    });
        }
    }
}
