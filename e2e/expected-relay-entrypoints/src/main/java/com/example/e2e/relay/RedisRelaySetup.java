package com.example.e2e.relay;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

@Configuration
public class RedisRelaySetup {
    static final String REFUND_METHOD = "onRefund";

    @Value("${relay.method:onCoupon}")
    private String relayMethod;

    @Bean
    public RedisMessageListenerContainer relayContainer(CouponChannelListener listener, CouponRelay relay) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.addMessageListener(listener, new ChannelTopic("coupon-events"));
        container.addMessageListener(new MessageListenerAdapter(relay, "onCoupon"), new ChannelTopic("coupons"));
        container.addMessageListener(new MessageListenerAdapter(relay, REFUND_METHOD), new ChannelTopic("refunds"));
        container.addMessageListener(new MessageListenerAdapter(relay), new ChannelTopic("vouchers"));
        return container;
    }

    @Bean
    public MessageListenerAdapter configuredAdapter(CouponRelay relay) {
        return new MessageListenerAdapter(relay, relayMethod);
    }
}
