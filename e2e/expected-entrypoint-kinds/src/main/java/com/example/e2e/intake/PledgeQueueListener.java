package com.example.e2e.intake;

import com.example.e2e.logging.ObservedContext;
import io.awspring.cloud.sqs.annotation.SqsListener;
import java.util.List;
import org.springframework.stereotype.Component;

/** One message at a time, and a batch: Spring Cloud AWS observes only the first, and only when switched on. */
@Component
public class PledgeQueueListener {
    private final PledgeFeed feed;

    PledgeQueueListener(PledgeFeed feed) {
        this.feed = feed;
    }

    @SqsListener("pledge-requests")
    public void onRequest(String pledgeId) {
        ObservedContext.entrypoint(IntakeMetrics.PLEDGE_QUEUE_LISTENER_ON_REQUEST)
                .mdc("pledgeId", pledgeId)
                .debugOnSuccess()
                .run(() -> {
                    feed.load();
                });
    }

    @SqsListener(queueNames = "pledge-bulk")
    public void onBulk(List<String> pledgeIds) {
        ObservedContext.entrypoint(IntakeMetrics.PLEDGE_QUEUE_LISTENER_ON_BULK)
                .debugOnSuccess()
                .run(() -> {
                    feed.load();
                });
    }
}
