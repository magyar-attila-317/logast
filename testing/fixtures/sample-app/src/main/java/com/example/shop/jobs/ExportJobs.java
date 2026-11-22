package com.example.shop.jobs;

import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Export work on a hand-made pool. */
@Component
public class ExportJobs {
    private final ExecutorService pool = Executors.newFixedThreadPool(4); // expect: UNBOUNDED_EXECUTOR
    /** #58 f: near miss, a LinkedBlockingQueue with a capacity is bounded. */
    private final ExecutorService retries = new ThreadPoolExecutor(1, 2, 60, TimeUnit.SECONDS, // expect-not: UNBOUNDED_EXECUTOR
            new LinkedBlockingQueue<>(100), new ThreadPoolExecutor.CallerRunsPolicy());

    public Future<String> start(String exportId) {
        return pool.submit(() -> "export-" + exportId); // expect: ASYNC_CONTEXT_LOSS
    }
}
