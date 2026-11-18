package com.example.e2e.intake;

import com.example.e2e.logging.ObservedContext;
import io.awspring.cloud.sqs.annotation.SqsListener;
import java.util.List;
import org.springframework.stereotype.Component;

/** One message at a time, and a batch: Spring Cloud AWS observes only the first, and only when switched on. */
@Component
public class CouponQueueListener {
    private final CouponFeed feed;

    CouponQueueListener(CouponFeed feed) {
        this.feed = feed;
    }

    @SqsListener("coupon-requests")
    public void onRequest(String couponId) {
        ObservedContext.entrypoint(IntakeMetrics.COUPON_QUEUE_LISTENER_ON_REQUEST)
                .mdc("couponId", couponId)
                .debugOnSuccess()
                .run(() -> {
                    feed.load();
                });
    }

    @SqsListener(queueNames = "coupon-bulk")
    public void onBulk(List<String> couponIds) {
        ObservedContext.entrypoint(IntakeMetrics.COUPON_QUEUE_LISTENER_ON_BULK)
                .debugOnSuccess()
                .run(() -> {
                    feed.load();
                });
    }
}
