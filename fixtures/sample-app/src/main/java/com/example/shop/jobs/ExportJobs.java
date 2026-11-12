package com.example.shop.jobs;

import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Export work on a hand-made pool. */
@Component
public class ExportJobs {
    private final ExecutorService pool = Executors.newFixedThreadPool(4); // expect: UNBOUNDED_EXECUTOR

    public Future<String> start(String exportId) {
        return pool.submit(() -> "export-" + exportId); // expect: ASYNC_CONTEXT_LOSS
    }
}
