package com.example.e2e.board;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class BoardMoveListener {
    private final BoardSweep sweep;

    public BoardMoveListener(BoardSweep sweep) {
        this.sweep = sweep;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMoved(BoardMoved event) {
        sweep.refresh();
    }
}
