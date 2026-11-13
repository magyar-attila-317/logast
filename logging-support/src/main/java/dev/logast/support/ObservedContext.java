package dev.logast.support;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.ReceiverContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An {@link Observation} (and therefore a span/trace) plus MDC entries around a unit of work, usually an
 * entrypoint that the framework does not observe itself (event listeners, message listeners, batch jobs).
 *
 * <pre>{@code
 * ObservedContext.entrypoint("shipment-listener.on-shipped")
 *         .mdc("order.id", event.orderId())
 *         .run(() -> {
 *             ...
 *         });
 * return ObservedContext.entrypoint("order-export").call(() -> exporter.export());   // with a result
 * }</pre>
 * The same by hand, for code a lambda does not fit (reassigned parameters, several checked exceptions):
 * <pre>{@code
 * ObservedContext observed = ObservedContext.entrypoint("shipment-listener.on-shipped")
 *         .mdc("order.id", event.orderId())
 *         .start();
 * try (observed) {
 *     ...
 * } catch (Throwable t) {
 *     observed.error(t);
 *     throw t;
 * } finally {
 *     observed.stop();
 * }
 * }</pre>
 * {@link #close()} restores MDC and closes the scope; it runs before the catch block, which is why recording the
 * error and stopping are separate calls (the pattern Micrometer documents for {@code openScope()}).
 * <p>
 * {@link #stop()} logs the outcome, with the MDC entries and the trace id of the context (put back for that one
 * line): {@code "<name> completed [durationMs=12]"} at INFO, or {@code "<name> failed [durationMs=12]"} at ERROR with
 * the exception passed to {@link #error}. That is the one place the failure is logged with its context: code below
 * should not log and rethrow. The exception is always rethrown: rollback, retries and dead-letter routing depend on it.
 * {@code describe("Shipment handling")} sets the subject, {@code debugOnSuccess()} lowers the success line
 * (per-message listeners), {@code failureLoggedByCaller()} lowers the failure line to DEBUG where the exception goes
 * back to code that logs it with its own handler (a synchronous event listener), {@code quiet()} turns both off.
 * MDC values are also attached to the observation as high-cardinality key values (span attributes).
 */
public final class ObservedContext implements AutoCloseable {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ObservedContext.class);

    private final Observation observation;
    private final Observation.Scope scope;
    private final MdcScope mdc;
    private final Map<String, Object> mdcEntries;
    private final String subject;
    private final Outcome outcome;
    private final long startNanos = System.nanoTime();
    private Throwable error;
    private boolean stopped;

    private enum Outcome { INFO, DEBUG_ON_SUCCESS, QUIET }

    private boolean failureLoggedByCaller;

    private ObservedContext(Observation observation, Observation.Scope scope, MdcScope mdc, Map<String, Object> mdcEntries,
                            String subject, Outcome outcome) {
        this.observation = observation;
        this.scope = scope;
        this.mdc = mdc;
        this.mdcEntries = mdcEntries;
        this.subject = subject;
        this.outcome = outcome;
    }

    public static Builder entrypoint(String name) {
        return new Builder(name, ObservationRegistryHolder.get());
    }

    public static Builder entrypoint(String name, ObservationRegistry registry) {
        return new Builder(name, registry);
    }

    /**
     * A task that runs in its own new observation (and trace) every time it executes: for periodic work started
     * with {@code scheduleAtFixedRate}/{@code scheduleWithFixedDelay}, which otherwise logs without any trace.
     */
    public static Runnable observed(String name, Runnable task) {
        java.util.Objects.requireNonNull(task, "task");
        return () -> {
            ObservedContext observed = entrypoint(name).start();
            try (observed) {
                task.run();
            } catch (RuntimeException | Error t) {
                observed.error(t);
                throw t;
            } finally {
                observed.stop();
            }
        };
    }

    /** Adds an MDC entry (restored on close) and a span attribute while the context is open. */
    public ObservedContext mdc(String key, Object value) {
        if (value != null) {
            mdc.and(key, value);
            mdcEntries.put(key, value);
            observation.highCardinalityKeyValue(key, String.valueOf(value));
        }
        return this;
    }

    public void error(Throwable t) {
        error = t;
        observation.error(t);
    }

    /** Logs the outcome (see the class comment) and stops the observation; a second call does nothing. */
    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        try {
            logOutcome();
        } finally {
            observation.stop();
        }
    }

    private void logOutcome() {
        boolean failed = error != null;
        boolean wanted = failed ? (failureLoggedByCaller ? LOG.isDebugEnabled() : LOG.isErrorEnabled())
                : (outcome == Outcome.INFO ? LOG.isInfoEnabled() : LOG.isDebugEnabled());
        if (outcome == Outcome.QUIET || !wanted) {
            return;
        }
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        // the scope and MDC were closed by close(), before the catch block: put them back for this one line
        try (Observation.Scope ignored = observation.openScope(); MdcScope m = MdcScope.empty()) {
            mdcEntries.forEach(m::and);
            if (failed && failureLoggedByCaller) {
                LOG.debug("{} failed [durationMs={}]: {}", subject, ms, String.valueOf(error));
            } else if (failed) {
                LOG.error("{} failed [durationMs={}]", subject, ms, error);
            } else if (outcome == Outcome.INFO) {
                LOG.info("{} completed [durationMs={}]", subject, ms);
            } else {
                LOG.debug("{} completed [durationMs={}]", subject, ms);
            }
        } catch (RuntimeException e) {
            // logging the outcome must never replace the work's own result or exception
        }
    }

    public Observation observation() {
        return observation;
    }

    @Override
    public void close() {
        mdc.close();
        scope.close();
    }

    /** A body that may throw: checked exceptions pass through {@link Builder#run} unchanged. */
    @FunctionalInterface
    public interface Work<E extends Throwable> {
        void run() throws E;
    }

    /** A body with a result: checked exceptions pass through {@link Builder#call} unchanged. */
    @FunctionalInterface
    public interface Result<T, E extends Throwable> {
        T call() throws E;
    }

    public static final class Builder {
        private final String name;
        private final ObservationRegistry registry;
        private final Map<String, Object> mdc = new LinkedHashMap<>();
        private final Map<String, String> tags = new LinkedHashMap<>();
        private Map<String, String> carrier;
        private String contextualName;
        private String subject;
        private Outcome outcome = Outcome.INFO;

        private Builder(String name, ObservationRegistry registry) {
            this.name = name;
            this.registry = registry == null ? ObservationRegistry.NOOP : registry;
        }

        public Builder mdc(String key, Object value) {
            mdc.put(key, value);
            return this;
        }

        /** Low-cardinality tag (metric dimension and span attribute). */
        public Builder tag(String key, String value) {
            tags.put(key, value);
            return this;
        }

        /** The subject of the outcome line: "Shipment handling" → "Shipment handling completed [durationMs=12]". */
        public Builder describe(String subject) {
            this.subject = subject;
            return this;
        }

        /** Success at DEBUG (failures stay ERROR): for entrypoints that run per message or per request. */
        public Builder debugOnSuccess() {
            this.outcome = Outcome.DEBUG_ON_SUCCESS;
            return this;
        }

        /**
         * The failure goes back to code that logs it (a synchronous event listener: the publisher's handler): the
         * failure line drops to DEBUG, so one failure is one ERROR. It is still recorded on the span and metrics.
         */
        public Builder failureLoggedByCaller() {
            this.failureLoggedByCaller = true;
            return this;
        }

        private boolean failureLoggedByCaller;

        /** No outcome lines at all (the code logs its own). */
        public Builder quiet() {
            this.outcome = Outcome.QUIET;
            return this;
        }

        public Builder contextualName(String contextualName) {
            this.contextualName = contextualName;
            return this;
        }

        /**
         * Continue a remote trace: {@code carrier} holds W3C headers ({@code traceparent}, {@code tracestate}),
         * extracted by the tracing bridge. With no usable carrier a new trace is started.
         */
        public Builder remoteParent(Map<String, String> carrier) {
            this.carrier = carrier;
            return this;
        }

        /** Starts the context, runs the work, records a failure, stops and logs the outcome. */
        public <E extends Throwable> void run(Work<E> work) throws E {
            ObservedContext observed = start();
            try (observed) {
                work.run();
            } catch (Throwable t) {
                observed.error(t);
                throw t;
            } finally {
                observed.stop();
            }
        }

        /** {@link #run} for work with a result. */
        public <T, E extends Throwable> T call(Result<T, E> work) throws E {
            ObservedContext observed = start();
            try (observed) {
                return work.call();
            } catch (Throwable t) {
                observed.error(t);
                throw t;
            } finally {
                observed.stop();
            }
        }

        public ObservedContext start() {
            Observation observation = carrier == null || carrier.isEmpty()
                    ? Observation.createNotStarted(name, registry)
                    : Observation.createNotStarted(name, () -> {
                        ReceiverContext<Map<String, String>> ctx = new ReceiverContext<>((c, key) -> c.get(key));
                        ctx.setCarrier(carrier);
                        return ctx;
                    }, registry);
            tags.forEach(observation::lowCardinalityKeyValue);
            if (contextualName != null) {
                observation.contextualName(contextualName);
            }
            mdc.forEach((k, v) -> {
                if (v != null) {
                    observation.highCardinalityKeyValue(k, String.valueOf(v));
                }
            });
            observation.start();
            Observation.Scope scope = observation.openScope();
            MdcScope m = MdcScope.empty();
            Map<String, Object> entries = new LinkedHashMap<>();
            mdc.forEach((k, v) -> {
                if (v != null) {
                    m.and(k, v);
                    entries.put(k, v);
                }
            });
            ObservedContext oc = new ObservedContext(observation, scope, m, entries, subject != null ? subject : name, outcome);
            oc.failureLoggedByCaller = failureLoggedByCaller;
            return oc;
        }
    }
}
