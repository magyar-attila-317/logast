package com.example.e2e.stock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class StockEvents {
    private final StockLedger ledger;

    public StockEvents(StockLedger ledger) {
        this.ledger = ledger;
    }

    @RabbitListener(queues = "stock-returns")
    public void onReturn(String itemId) {
        log.info("Return started"); // logast:wording
        ledger.refill(itemId);
        log.info("Stock events (return) completed"); // logast:wording
        StockMetrics.stockEventsCompleted();
    }

    @RabbitListener(queues = "stock-audits", ackMode = "MANUAL")
    public void onAudit(String itemId) {
        log.info("Audit started"); // logast:wording
        ledger.refill(itemId);
        log.info("Stock events (audit) completed"); // logast:wording
        StockMetrics.stockEventsCompleted();
    }
}
