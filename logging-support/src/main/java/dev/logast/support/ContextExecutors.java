package dev.logast.support;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;

/**
 * Wraps executors that Spring does not manage so tasks run with the submitter's MDC and trace context:
 * {@code private final ExecutorService executor = ContextExecutors.wrap(Executors.newFixedThreadPool(2));}
 */
public final class ContextExecutors {
    private ContextExecutors() {
    }

    public static Executor wrap(Executor delegate) {
        return command -> delegate.execute(ContextPropagation.wrap(command));
    }

    public static ExecutorService wrap(ExecutorService delegate) {
        return new ContextExecutorService<>(delegate);
    }

    public static ScheduledExecutorService wrap(ScheduledExecutorService delegate) {
        return new ContextScheduledExecutorService(delegate);
    }

    private static class ContextExecutorService<E extends ExecutorService> extends AbstractExecutorService {
        final E delegate;

        ContextExecutorService(E delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(ContextPropagation.wrap(command));
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
            return delegate.invokeAll(tasks.stream().map(ContextPropagation::wrap).toList());
        }
    }

    private static final class ContextScheduledExecutorService extends ContextExecutorService<ScheduledExecutorService>
            implements ScheduledExecutorService {
        ContextScheduledExecutorService(ScheduledExecutorService delegate) {
            super(delegate);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            return delegate.schedule(ContextPropagation.wrap(command), delay, unit);
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            return delegate.schedule(ContextPropagation.wrap(callable), delay, unit);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            return delegate.scheduleAtFixedRate(ContextPropagation.wrap(command), initialDelay, period, unit);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            return delegate.scheduleWithFixedDelay(ContextPropagation.wrap(command), initialDelay, delay, unit);
        }
    }
}
