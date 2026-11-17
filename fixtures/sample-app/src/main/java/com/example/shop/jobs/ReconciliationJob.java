package com.example.shop.jobs;

import com.example.shop.domain.Order;
import com.example.shop.repository.OrderRepository;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ReconciliationJob {
    private final OrderRepository repository;

    public ReconciliationJob(OrderRepository repository) {
        this.repository = repository;
    }

    @Scheduled(fixedDelay = 60000)
    public void reconcile() { // expect: JOB_OUTCOME_MISSING, SCHEDULED_WITHOUT_LOCK
        MDC.put("job.name", "reconciliation"); // expect: MDC_NOT_CLOSEABLE
        for (Order order : repository.findAll()) { // expect: ROW_BY_ROW_WRITE
            if (order.lines().isEmpty()) {
                repository.delete(order.id());
            }
        }
    }

    /** #58 f: near miss, the loop over the query result only collects; the write is one call after it. */
    public void purgeEmpty() {
        List<String> empty = new ArrayList<>();
        for (Order order : repository.findAll()) { // expect-not: ROW_BY_ROW_WRITE
            if (order.lines().isEmpty()) {
                empty.add(order.id());
            }
        }
        repository.deleteAll(empty);
    }
}
