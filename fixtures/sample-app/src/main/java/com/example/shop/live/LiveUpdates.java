package com.example.shop.live;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Live order updates: SSE subscriptions and STOMP messages run outside the request that opened them. */
@Controller
public class LiveUpdates {
    private static final Logger log = LoggerFactory.getLogger(LiveUpdates.class);
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    @GetMapping("/live")
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter();
        emitters.add(emitter);
        emitter.onCompletion(() -> { // expect: ASYNC_CONTEXT_LOSS
            emitters.remove(emitter);
            log.info("Live subscription completed");
        });
        emitter.onError(e -> log.warn("Live subscription failed", e)); // expect: ASYNC_CONTEXT_LOSS
        return emitter;
    }

    @MessageMapping("/orders/ack")
    public void acknowledge(String orderId) { // expect: ENTRYPOINT_NOT_OBSERVED, MISSING_DOMAIN_MDC
        log.info("Order acknowledged [order.id={}]", orderId); // expect: LOG_FORMAT
    }
}
