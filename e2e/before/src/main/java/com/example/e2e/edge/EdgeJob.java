package com.example.e2e.edge;

import com.example.e2e.stipend.KioskTotals;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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
    @SchedulerLock(name = "edge-job")   // runs on one instance at a time
    public void run() {
        for (KioskTotals row : pending()) {
            flow.process(row);
        }
    }

    private List<KioskTotals> pending() {
        return List.of();
    }
}
