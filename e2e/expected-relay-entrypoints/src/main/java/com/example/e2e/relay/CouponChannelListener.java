package com.example.e2e.relay;

import com.example.e2e.logging.ObservedContext;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/** A Redis pub/sub listener registered on the container. */
@Component
public class CouponChannelListener implements MessageListener {
    private final RelayLog relayLog;

    CouponChannelListener(RelayLog relayLog) {
        this.relayLog = relayLog;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        ObservedContext.entrypoint(RelayMetrics.COUPON_CHANNEL_LISTENER_ON_MESSAGE)
                .debugOnSuccess()
                .run(() -> {
                    relayLog.forward(message.getBody());
                });
    }
}
