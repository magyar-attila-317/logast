package com.example.e2e.stock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class StockRejects {
    @RabbitListener(queues = "stock-rejects")
    public void onReject(String itemId) {
        if (itemId.isBlank()) {
            throw new AmqpRejectAndDontRequeueException("blank item id");
        }
        log.info("Stock rejects completed"); // logast:wording
        StockMetrics.stockRejectsCompleted(); // logast:metric review, confidence: low
    }
}
