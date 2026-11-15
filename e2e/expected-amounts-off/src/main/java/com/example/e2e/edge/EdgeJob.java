package com.example.e2e.edge;

import com.example.e2e.stipend.KioskTotals;
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
        log.info("Edge started"); // logast:wording
        var rows = pending();
        for (KioskTotals row : rows) {
            flow.process(row);
        }
        log.info("Edge completed [kioskTotalsCount={}]", rows.size()); // logast:wording
    }

    private List<KioskTotals> pending() {
        return List.of();
    }
}
