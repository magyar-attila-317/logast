package com.example.e2e.board;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class BoardSweep {
    private static final Logger log = LoggerFactory.getLogger(BoardSweep.class);

    public void refresh() {
        summary(3, 0, 12L);
    }

    void summary(int processed, int failed, long elapsedMs) {
        String line = "Board sweep summary: processed={} failed={} elapsedMs={}";
        Object[] args = {processed, failed, elapsedMs};
        if (failed > 0) {
            log.error(line, args);
        } else {
            log.info(line, args);
        }
    }

    public void each(List<String> boards) {
        for (String board : boards) {
            long start = System.nanoTime();
            try {
                clean(board);
                log.info("Board cleanup: board={} outcome=OK elapsedMs={}", board, (System.nanoTime() - start) / 1_000_000);
            } catch (RuntimeException e) {
                log.error("Board cleanup: board={} outcome=FAILED elapsedMs={}", board, (System.nanoTime() - start) / 1_000_000);
                throw e;
            }
        }
    }

    public int parse(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid board size: " + e.getMessage(), e
            );
        }
    }

    void clean(String board) {
    }

    public void recover(String boardId, String orderId, String tenant) {
        try {
            clean(boardId);
        } catch (IllegalStateException e) {
            log.error("BOARD_RECOVERY_FAILED: board {} order {} tenant {}", boardId, orderId, tenant, e);
        }
    }
}
