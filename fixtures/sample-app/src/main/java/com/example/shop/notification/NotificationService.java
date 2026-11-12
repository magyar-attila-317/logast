package com.example.shop.notification;

import com.example.shop.domain.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final Mailer mailer;
    private final ExecutorService executor = Executors.newFixedThreadPool(2); // expect: UNBOUNDED_EXECUTOR

    public NotificationService(Mailer mailer) {
        this.mailer = mailer;
    }

    @Async
    public void sendConfirmation(Order order) { // expect: ASYNC_CONTEXT_LOSS
        log.info("Sending confirmation for order {}", order.id()); // expect: LOG_FORMAT
        executor.submit(() -> mailer.send(order.email(), "Thanks for your order")); // expect: ASYNC_CONTEXT_LOSS
    }

    void pause() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
