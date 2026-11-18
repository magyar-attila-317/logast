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

    @Value("${relay.method:onPledge}")
    private String relayMethod;

    @Bean
    public RedisMessageListenerContainer relayContainer(PledgeChannelListener listener, PledgeRelay relay) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.addMessageListener(listener, new ChannelTopic("pledge-events"));
        container.addMessageListener(new MessageListenerAdapter(relay, "onPledge"), new ChannelTopic("pledges"));
        container.addMessageListener(new MessageListenerAdapter(relay, REFUND_METHOD), new ChannelTopic("refunds"));
        container.addMessageListener(new MessageListenerAdapter(relay), new ChannelTopic("vouchers"));
        return container;
    }

    @Bean
    public MessageListenerAdapter configuredAdapter(PledgeRelay relay) {
        return new MessageListenerAdapter(relay, relayMethod);
    }
}
