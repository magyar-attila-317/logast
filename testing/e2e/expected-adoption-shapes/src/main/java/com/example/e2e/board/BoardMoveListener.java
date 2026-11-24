package com.example.e2e.board;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
public class BoardMoveListener {
    private final BoardSweep sweep;

    public BoardMoveListener(BoardSweep sweep) {
        this.sweep = sweep;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMoved(BoardMoved event) {
        sweep.refresh();
        log.info("Board move completed"); // logast:wording
        BoardMetrics.boardMoveCompleted(); // logast:metric review, confidence: low
    }
}
