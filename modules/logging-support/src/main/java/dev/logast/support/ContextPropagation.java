package dev.logast.support;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.Callable;

/** Captures MDC and registered thread locals (current Observation/span) and restores them in another thread. */
public final class ContextPropagation {
    private static final ContextSnapshotFactory FACTORY = ContextSnapshotFactory.builder().build();

    private ContextPropagation() {
    }

    public static Runnable wrap(Runnable task) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        ContextSnapshot snapshot = FACTORY.captureAll();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try (ContextSnapshot.Scope ignored = snapshot.setThreadLocals()) {
                restore(mdc);
                task.run();
            } finally {
                MdcHygiene.report("an async task", MdcHygiene.leaked(mdc, MDC.getCopyOfContextMap()));
                // restore, never clear: with a caller-runs policy this is the caller's own thread and context
                restore(previous);
            }
        };
    }

    /** Same as {@link #wrap(Runnable)}; a distinct name so lambdas never resolve to the Callable overload. */
    public static Runnable wrapRunnable(Runnable task) {
        return wrap(task);
    }

    /** A callback that runs with the MDC and trace of the thread that registered it (e.g. {@code SseEmitter.onError}). */
    public static <T> java.util.function.Consumer<T> wrapConsumer(java.util.function.Consumer<T> callback) {
        java.util.Objects.requireNonNull(callback, "callback");
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        ContextSnapshot snapshot = FACTORY.captureAll();
        return value -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try (ContextSnapshot.Scope ignored = snapshot.setThreadLocals()) {
                restore(mdc);
                callback.accept(value);
            } finally {
                restore(previous);
            }
        };
    }

    public static <T> Callable<T> wrap(Callable<T> task) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        ContextSnapshot snapshot = FACTORY.captureAll();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try (ContextSnapshot.Scope ignored = snapshot.setThreadLocals()) {
                restore(mdc);
                return task.call();
            } finally {
                MdcHygiene.report("an async task", MdcHygiene.leaked(mdc, MDC.getCopyOfContextMap()));
                restore(previous);
            }
        };
    }

    private static void restore(Map<String, String> mdc) {
        if (mdc == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(mdc);
        }
    }
}
