package com.example.e2e.board;

import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class BoardPoller {
    private final BoardSweep sweep;

    public BoardPoller(BoardSweep sweep) {
        this.sweep = sweep;
    }

    @Scheduled(fixedDelayString = "${board.poll.interval-seconds:60}", timeUnit = TimeUnit.SECONDS)
    public void poll() {
        sweep.refresh();
    }
}
