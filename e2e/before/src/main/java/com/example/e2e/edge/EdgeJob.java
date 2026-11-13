package com.example.e2e.edge;

import com.example.e2e.income.SalesTotals;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Drives the edge-case shapes row by row. */
@Component
public class EdgeJob {
    private final EdgeFlow flow;

    public EdgeJob(EdgeFlow flow) {
        this.flow = flow;
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void run() {
        for (SalesTotals row : pending()) {
            flow.process(row);
        }
    }

    private List<SalesTotals> pending() {
        return List.of();
    }
}
