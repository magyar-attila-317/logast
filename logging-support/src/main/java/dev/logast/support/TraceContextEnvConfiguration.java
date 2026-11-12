package dev.logast.support;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Connects {@link TraceContextEnv} to the application's Tracer and Propagator (W3C traceparent by default). */
@AutoConfiguration(afterName = "org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration")
@ConditionalOnClass(name = "io.micrometer.tracing.Tracer")
public class TraceContextEnvConfiguration {

    @Bean
    public SmartInitializingSingleton traceContextEnvInitializer(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        return () -> {
            Tracer t = tracer.getIfAvailable();
            Propagator p = propagator.getIfAvailable();
            if (t == null || p == null) {
                return;
            }
            TraceContextEnv.source(() -> {
                Span span = t.currentSpan();
                if (span == null) {
                    return Map.of();
                }
                Map<String, String> headers = new HashMap<>();
                p.inject(span.context(), headers, Map::put);
                Map<String, String> env = new HashMap<>();
                headers.forEach((k, v) -> {
                    String key = k.toLowerCase(Locale.ROOT);
                    if (key.equals("traceparent") || key.equals("tracestate")) {
                        env.put(key.toUpperCase(Locale.ROOT), v);
                    }
                });
                return env;
            });
        };
    }
}
