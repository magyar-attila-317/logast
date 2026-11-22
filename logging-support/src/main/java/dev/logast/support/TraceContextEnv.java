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

    /**
     * #234: where a Quartz job keeps its trace for the {@code JobListener} that starts the next job of a chain
     * ({@code context.put(TraceContextEnv.KEY, TraceContextEnv.current())}): the listener runs after the job's
     * observation has ended, so it reads the trace from there.
     */
    public static final String KEY = "logast.traceContext";

    /** #234: the trace a value stored under {@link #KEY} holds; empty for anything else (never null). */
    public static Map<String, String> from(Object stored) {
        if (!(stored instanceof Map<?, ?> m)) {
            return Map.of();
        }
        Map<String, String> out = new java.util.LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (k instanceof String key && v instanceof String value && (key.equals("TRACEPARENT") || key.equals("TRACESTATE"))) {
                out.put(key, value);
            }
        });
        return out;
    }

    /**
     * #234: W3C headers ({@code traceparent}, {@code tracestate}) for {@code ObservedContext...remoteParent(..)} from a
     * map that carries {@link #current()}'s entries, such as a Quartz job's merged JobDataMap.
     */
    public static Map<String, String> carrier(Map<?, ?> data) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (data != null) {
            for (String key : new String[]{"TRACEPARENT", "TRACESTATE"}) {
                if (data.get(key) instanceof String v && !v.isBlank()) {
                    out.put(key.toLowerCase(java.util.Locale.ROOT), v);
                }
            }
        }
        return out;
    }

    /** Set by {@link TraceContextEnvConfiguration} when Micrometer Tracing is present. */
    public static void source(Supplier<Map<String, String>> supplier) {
        source = supplier == null ? Map::of : supplier;
    }
}
