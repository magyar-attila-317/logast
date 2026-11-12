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
 * MDC values are also attached to the observation as high-cardinality key values (span attributes).
 */
public final class ObservedContext implements AutoCloseable {
    private final Observation observation;
    private final Observation.Scope scope;
    private final MdcScope mdc;

    private ObservedContext(Observation observation, Observation.Scope scope, MdcScope mdc) {
        this.observation = observation;
        this.scope = scope;
        this.mdc = mdc;
    }

    public static Builder entrypoint(String name) {
        return new Builder(name, ObservationRegistryHolder.get());
    }

    public static Builder entrypoint(String name, ObservationRegistry registry) {
        return new Builder(name, registry);
    }

    /** Adds an MDC entry (restored on close) and a span attribute while the context is open. */
    public ObservedContext mdc(String key, Object value) {
        if (value != null) {
            mdc.and(key, value);
            observation.highCardinalityKeyValue(key, String.valueOf(value));
        }
        return this;
    }

    public void error(Throwable t) {
        observation.error(t);
    }

    public void stop() {
        observation.stop();
    }

    public Observation observation() {
        return observation;
    }

    @Override
    public void close() {
        mdc.close();
        scope.close();
    }

    public static final class Builder {
        private final String name;
        private final ObservationRegistry registry;
        private final Map<String, Object> mdc = new LinkedHashMap<>();
        private final Map<String, String> tags = new LinkedHashMap<>();
        private Map<String, String> carrier;
        private String contextualName;

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
            mdc.forEach(m::and);
            return new ObservedContext(observation, scope, m);
        }
    }
}
