package com.example.e2e.shelf;

import io.micrometer.core.annotation.Counted;
import io.micrometer.core.annotation.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ShelfKeeper {
    private static final Logger log = LoggerFactory.getLogger(ShelfKeeper.class);

    @Timed("shelf.restock")
    public void restock(String shelfCode) {
        log.info("Shelf restock started");
        log.info("Shelf restock completed");
    }

    @Counted("shelf.inspection")
    public void inspect(String shelfCode) {
        log.info("Shelf inspection started");
        log.info("Shelf inspection completed");
    }
}
