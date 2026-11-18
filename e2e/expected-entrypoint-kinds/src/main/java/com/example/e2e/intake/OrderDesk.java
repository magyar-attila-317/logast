package com.example.e2e.intake;

import com.example.e2e.logging.MdcScope;
import com.example.e2e.order.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OrderDesk {
    private final PledgeFeed feed;

    OrderDesk(PledgeFeed feed) {
        this.feed = feed;
    }

    public void accept(Order order) {
        try (var mdc = MdcScope.of("orderId", order.id())) {
            feed.load();
            log.info("Order desk completed"); // logast:wording
            IntakeMetrics.orderDeskCompleted(); // logast:metric review, confidence: low
        }
    }
}
