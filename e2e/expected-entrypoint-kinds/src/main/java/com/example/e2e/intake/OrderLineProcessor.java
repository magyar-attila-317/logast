package com.example.e2e.intake;

import com.example.e2e.logging.ObservedContext;
import com.example.e2e.order.Order;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

/** A chunk step's processor: called once per order read. */
@Component
public class OrderLineProcessor implements ItemProcessor<Order, String> {
    private final CouponFeed feed;

    OrderLineProcessor(CouponFeed feed) {
        this.feed = feed;
    }

    @Override
    public String process(Order order) {
        return ObservedContext.entrypoint(IntakeMetrics.ORDER_LINE_PROCESSOR_PROCESS)
                .mdc("orderId", order.id())
                .debugOnSuccess()
                .call(() -> {
                    feed.load();
                    return order.customerId() + ":" + order.amountCents();
                });
    }
}
