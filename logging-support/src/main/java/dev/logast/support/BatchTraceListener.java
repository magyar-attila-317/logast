package dev.logast.support;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Batch jobs: one Observation (span) around all CommandLineRunner/ApplicationRunner beans (only when the
 * application has any). Spring Boot calls the
 * runners on the main thread between ApplicationStartedEvent and ApplicationReadyEvent, so no proxies are needed.
 *
 * <p>Trace parent, in this order: {@code TRACEPARENT} (+ {@code TRACESTATE}) as defined by the OpenTelemetry
 * environment-variable carrier spec; {@code TRACE_ID} (32 hex chars) for orchestrators that only know a trace id;
 * otherwise a new trace is started. Both are read through the Spring Environment, so system properties and
 * application properties work as well (handy for tests).
 */
public final class BatchTraceListener implements ApplicationListener<ApplicationEvent> {
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObservationRegistry registry;
    private final Environment environment;
    private ObservedContext context;

    public BatchTraceListener(ObservationRegistry registry, Environment environment) {
        this.registry = registry;
        this.environment = environment;
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof ApplicationStartedEvent started && context == null && hasRunners(started)) {
            String name = environment.getProperty("spring.application.name", "batch");
            context = ObservedContext.entrypoint("batch.run", registry)
                    .contextualName(name)
                    .tag("batch.name", name)
                    .remoteParent(carrier(environment))
                    .start();
        } else if (event instanceof ApplicationReadyEvent && context != null) {
            finish(null);
        } else if (event instanceof ApplicationFailedEvent failed && context != null) {
            finish(failed.getException());
        }
    }

    /** Only applications with runners are batch-like; whether spring-web is on the classpath does not matter. */
    private static boolean hasRunners(ApplicationStartedEvent event) {
        var ctx = event.getApplicationContext();
        return ctx.getBeanNamesForType(CommandLineRunner.class).length > 0
                || ctx.getBeanNamesForType(ApplicationRunner.class).length > 0;
    }

    private void finish(Throwable error) {
        ObservedContext c = context;
        context = null;
        c.close();
        if (error != null) {
            c.error(error);
        }
        c.stop();
    }

    static Map<String, String> carrier(Environment env) {
        Map<String, String> carrier = new LinkedHashMap<>();
        String traceparent = env.getProperty("TRACEPARENT");
        if (traceparent != null && !traceparent.isBlank()) {
            carrier.put("traceparent", traceparent.trim());
            String tracestate = env.getProperty("TRACESTATE");
            if (tracestate != null && !tracestate.isBlank()) {
                carrier.put("tracestate", tracestate.trim());
            }
            return carrier;
        }
        String traceId = env.getProperty("TRACE_ID");
        if (traceId != null && TRACE_ID.matcher(traceId.trim().toLowerCase()).matches()) {
            byte[] span = new byte[8];
            RANDOM.nextBytes(span);
            carrier.put("traceparent", "00-" + traceId.trim().toLowerCase() + "-" + HexFormat.of().formatHex(span) + "-01");
        }
        return carrier;
    }
}
