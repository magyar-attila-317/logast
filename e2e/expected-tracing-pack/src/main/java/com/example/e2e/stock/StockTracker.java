package com.example.e2e.stock;

import io.micrometer.observation.annotation.Observed;
import io.micrometer.tracing.annotation.NewSpan;
import java.util.HashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
class StockTracker {
    private final Set<String> picked = new HashSet<>();

    @EventListener(ApplicationReadyEvent.class)
    @Observed(name = "stock.warm-up")
    void warmUp() {
        picked.clear();
        log.info("Stock tracker completed"); // logast:wording
        StockMetrics.stockTrackerCompleted(); // logast:metric review, confidence: low
    }

    @EventListener
    void onPicked(StockPicked event) {
        picked.add(event.stockCode());
        log.info("Stock tracker completed"); // logast:wording
        StockMetrics.stockTrackerCompleted(); // logast:metric review, confidence: low
    }

    @NewSpan("stock.count")
    int count() {
        return picked.size();
    }
}
