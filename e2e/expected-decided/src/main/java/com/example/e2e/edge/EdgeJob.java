package com.example.e2e.edge;

import com.example.e2e.income.SalesTotals;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Drives the edge-case shapes row by row. */
@Slf4j
@Component
public class EdgeJob {
    private final EdgeFlow flow;

    public EdgeJob(EdgeFlow flow) {
        this.flow = flow;
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void run() {
        log.info("Coupon sweep started");
        var rows = pending();
        for (SalesTotals row : rows) {
            flow.process(row);
        }
        log.info("Coupon sweep completed [salesTotalsCount={}]", rows.size());
    }

    private List<SalesTotals> pending() {
        return List.of();
    }
}
