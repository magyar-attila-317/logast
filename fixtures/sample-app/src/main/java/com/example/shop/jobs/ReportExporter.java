package com.example.shop.jobs;

import com.example.shop.domain.Order;
import com.example.shop.domain.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ReportExporter {
    private static final Logger log = LoggerFactory.getLogger(ReportExporter.class);

    public void export(List<Order> orders) {
        log.info("Starting report export [count={}]", orders.size()); // expect: MESSAGE_PHRASING
        for (Order order : orders) {
            log.info("Report line exported [order.id={}]", order.id()); // expect: LOG_LEVEL
        }
        new Thread(() -> write(orders)).start(); // expect: ASYNC_CONTEXT_LOSS
        orders.parallelStream().forEach(this::writeOne); // expect: ASYNC_CONTEXT_LOSS
    }

    private void write(List<Order> orders) {
        log.info("Report written [count={}]", orders.size());
    }

    private void writeOne(Order order) {
        log.debug("Report line written");
    }

    public void exportSafely(List<Order> orders) {
        try {
            render(orders);
        } catch (IllegalStateException e) {
            log.warn("Report export failed", e);
        }
    }

    private void render(List<Order> orders) {
        try {
            if (orders.isEmpty()) {
                throw new IllegalStateException("nothing to render");
            }
        } catch (IllegalStateException e) {
            log.error("Report rendering failed", e); // expect: EXCEPTION_LOGGED_TWICE
            throw e;
        }
    }

    private static final List<String> CHANNELS = List.of("email", "sftp");

    /** Bounded loops (enum values, literal lists, constants): a fixed handful of lines, not noise. */
    public void trackStatuses() {
        for (OrderStatus status : OrderStatus.values()) {
            log.info("Status tracked [status={}]", status);
        }
        List.of("daily", "weekly").forEach(period -> log.info("Report period enabled [period={}]", period));
        for (String channel : CHANNELS) {
            log.info("Report channel enabled [channel={}]", channel);
        }
    }
}
