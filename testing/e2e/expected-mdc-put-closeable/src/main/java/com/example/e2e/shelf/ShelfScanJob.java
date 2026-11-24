package com.example.e2e.shelf;

import com.example.e2e.logging.MdcScope;
import com.example.e2e.logging.ObservedContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ShelfScanJob {
    private static final Logger log = LoggerFactory.getLogger(ShelfScanJob.class);

    @Scheduled(fixedDelay = 60000)
    public void scan() {
        String shelfCode = nextShelf();
        try (var mdc = MdcScope.of("shelfCode", shelfCode)) {
            count(shelfCode);
        }
    }

    void count(String shelfCode) {
        log.info("Shelf count started");
    }

    void step(String scanStep) {
        try (var mdc = MdcScope.of("scanStep", scanStep)) {
            log.info("Scan step started");
        }
    }

    void phases() {
        try (MdcScope outer = MdcScope.of("scanPhase", "outer")) {
            try (MdcScope inner = MdcScope.of("scanPhase", "inner")) {
                log.info("Inner phase started");
            }
            log.info("Outer phase completed");
        }
    }

    void label(String shelfRef) {
        ObservedContext.entrypoint(ShelfMetrics.SHELF_LABELLING)
                .quiet()
                .run(() -> {
                    try (MdcScope c = MdcScope.of("shelfCode", shelfRef)) {
                        log.info("Shelf labelling started");
                    }
                });
    }

    void loose(String shelfRef) {
        MDC.putCloseable("shelfCode", shelfRef);
        log.info("Loose scan started");
    }

    private String nextShelf() {
        return "S1";
    }
}
