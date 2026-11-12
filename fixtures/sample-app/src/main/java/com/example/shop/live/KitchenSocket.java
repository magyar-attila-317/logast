package com.example.shop.live;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** Raw WebSocket handler: each message arrives without a trace. */
public class KitchenSocket extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(KitchenSocket.class);

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) { // expect: ENTRYPOINT_NOT_OBSERVED, JOB_OUTCOME_MISSING
        log.info("Kitchen message received");
    }
}
