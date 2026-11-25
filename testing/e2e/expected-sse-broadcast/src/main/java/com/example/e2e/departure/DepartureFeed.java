package com.example.e2e.departure;

import com.example.e2e.logging.ContextPropagation;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class DepartureFeed {
    private static final Logger log = LoggerFactory.getLogger(DepartureFeed.class);
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final SimpMessagingTemplate messaging;

    public DepartureFeed(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    public SseEmitter subscribe(String subscriberId) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.put(subscriberId, emitter);
        emitter.onCompletion(ContextPropagation.wrapRunnable(() -> emitters.remove(subscriberId)));
        return emitter;
    }

    public void publish(String departure) {
        emitters.forEach((subscriberId, emitter) -> {
            try {
                emitter.send(departure);
            } catch (IOException e) {
                log.warn("Departure push failed", e);
                emitters.remove(subscriberId);
            }
        });
        messaging.convertAndSend("/topic/departures", departure);
    }

    public void delay(String departure) {
        for (Map.Entry<String, SseEmitter> entry : emitters.entrySet()) {
            try {
                entry.getValue().send(departure);
            } catch (IOException e) {
                log.warn("Delay push to subscriber {} failed", entry.getKey(), e);
                DepartureMetrics.pushDelayFailed();
            }
        }
    }

    public void cancel(String departure) {
        for (SseEmitter emitter : emitters.values()) {
            try {
                emitter.send(departure);
            } catch (IOException e) {
                log.warn("Cancellation push failed [emitter={}]", emitter, e);
                DepartureMetrics.pushCancellationFailed();
            }
        }
    }

    public void heartbeat() throws IOException {
        for (SseEmitter emitter : emitters.values()) {
            emitter.send(SseEmitter.event().comment("ping"));
        }
    }
}
