package com.example.e2e.intake;

import com.example.e2e.logging.MdcScope;
import com.example.e2e.order.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Its own flow after the commit: an MdcScope (the order's), which measures nothing. */
@Component
public class OrderLedger {
    private final PledgeFeed feed;

    OrderLedger(PledgeFeed feed) {
        this.feed = feed;
    }

    @TransactionalEventListener
    public void onSettled(Order order) {
        try (var mdc = MdcScope.of("orderId", order.id())) {
            feed.load();
        }
    }
}
