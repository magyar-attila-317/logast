package com.example.e2e.departure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;

/** Runs on the WebSocket session's thread before the inbound channel hands the frame to its executor. */
public class StationInterceptor implements ChannelInterceptor {
    private static final Logger log = LoggerFactory.getLogger(StationInterceptor.class);

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        log.debug("Inbound frame {} for {}", accessor.getCommand(), accessor.getDestination());
        return message;
    }
}
