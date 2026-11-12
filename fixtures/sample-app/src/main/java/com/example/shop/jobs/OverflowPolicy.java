package com.example.shop.jobs;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/** Re-runs already submitted (and already wrapped) tasks: must not be reported as context loss. */
public class OverflowPolicy implements RejectedExecutionHandler {
    @Override
    public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
        if (executor.isShutdown()) {
            new Thread(task).start();
            return;
        }
        executor.getQueue().poll();
        executor.execute(task);
    }
}
