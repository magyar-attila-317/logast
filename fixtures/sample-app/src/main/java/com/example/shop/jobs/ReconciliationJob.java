package com.example.shop.jobs;

import com.example.shop.domain.Order;
import com.example.shop.repository.OrderRepository;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationJob {
    private final OrderRepository repository;

    public ReconciliationJob(OrderRepository repository) {
        this.repository = repository;
    }

    @Scheduled(fixedDelay = 60000)
    public void reconcile() { // expect: ENTRYPOINT_SILENT, JOB_OUTCOME_MISSING
        MDC.put("job.name", "reconciliation"); // expect: MDC_NOT_CLOSEABLE
        for (Order order : repository.findAll()) { // expect: ROW_BY_ROW_WRITE
            if (order.lines().isEmpty()) {
                repository.delete(order.id());
            }
        }
    }
}
