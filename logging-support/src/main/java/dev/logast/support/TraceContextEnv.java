package dev.logast.support;

import java.util.Map;
import java.util.function.Supplier;

/**
 * The current trace context as environment variables, for handing a trace to another process: an orchestrator
 * starting a Kubernetes job pod or a local process. The receiving side is {@link BatchTraceListener}
 * ({@code TRACEPARENT} / {@code TRACESTATE}, the OpenTelemetry environment-variable carrier).
 *
 * <pre>{@code
 * processBuilder.environment().putAll(TraceContextEnv.current());
 * containerBuilder.addAllToEnv(TraceContextEnv.current().entrySet().stream()
 *         .map(e -> new EnvVar(e.getKey(), e.getValue(), null)).toList());
 * }</pre>
 * Empty when there is no current span or no tracer (never null).
 */
public final class TraceContextEnv {
    private static volatile Supplier<Map<String, String>> source = Map::of;

    private TraceContextEnv() {
    }

    public static Map<String, String> current() {
        Map<String, String> env = source.get();
        return env == null ? Map.of() : env;
    }

    /** Set by {@link TraceContextEnvConfiguration} when Micrometer Tracing is present. */
    public static void source(Supplier<Map<String, String>> supplier) {
        source = supplier == null ? Map::of : supplier;
    }
}
