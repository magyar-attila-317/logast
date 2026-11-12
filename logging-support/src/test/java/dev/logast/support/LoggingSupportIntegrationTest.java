package dev.logast.support;

import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = LoggingSupportIntegrationTest.App.class, properties = {
        "TRACEPARENT=00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
        "management.tracing.sampling.probability=1.0",
        "spring.application.name=nightly-reconciliation",
        "management.otlp.metrics.export.enabled=false",
        "management.tracing.export.otlp.enabled=false",
        "management.logging.export.otlp.enabled=false"})
class LoggingSupportIntegrationTest {
    static final AtomicReference<String> RUNNER_TRACE = new AtomicReference<>();

    @SpringBootApplication
    @EnableAsync
    static class App {
        @Bean
        AsyncWorker asyncWorker() {
            return new AsyncWorker();
        }

        @Bean
        CommandLineRunner runner(Tracer tracer) {
            return args -> RUNNER_TRACE.set(tracer.currentSpan() == null ? null : tracer.currentSpan().context().traceId());
        }
    }

    static class AsyncWorker {
        @Async
        public CompletableFuture<Map<String, String>> capture() {
            return CompletableFuture.completedFuture(MDC.getCopyOfContextMap());
        }
    }

    @Autowired
    AsyncWorker worker;
    @Autowired
    Environment environment;
    @Autowired
    TaskDecorator decorator;

    @Test
    void batchRunContinuesTraceFromEnvironment() {
        assertThat(RUNNER_TRACE.get()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    void asyncKeepsMdc() throws Exception {
        assertThat(decorator).isNotNull();
        try (var mdc = MdcScope.of("order.id", "o-7")) {
            assertThat(worker.capture().get()).containsEntry("order.id", "o-7");
        }
    }

    @Test
    void ecsDefaultsWithServiceNameFallback() {
        assertThat(environment.getProperty("logging.structured.format.console")).isEqualTo("ecs");
        assertThat(environment.getProperty("logging.structured.ecs.service.name")).isEqualTo("nightly-reconciliation");
    }

    @Autowired
    Tracer tracer;

    @Test
    void traceContextEnvHandsTheTraceToAJobPod() {
        assertThat(TraceContextEnv.current()).isEmpty(); // no current span
        var span = tracer.nextSpan().name("orchestrator.launch").start();
        try (var ws = tracer.withSpan(span)) {
            Map<String, String> env = TraceContextEnv.current();
            assertThat(env.get("TRACEPARENT")).contains(span.context().traceId());
            // what the job pod's BatchTraceListener reads back
            var podEnv = new org.springframework.mock.env.MockEnvironment().withProperty("TRACEPARENT", env.get("TRACEPARENT"));
            assertThat(BatchTraceListener.carrier(podEnv).get("traceparent")).contains(span.context().traceId());
        } finally {
            span.end();
        }
    }

    @Test
    void traceIdFallbackBuildsTraceparent() {
        var env = new org.springframework.mock.env.MockEnvironment().withProperty("TRACE_ID", "0AF7651916CD43DD8448EB211C80319C");
        assertThat(BatchTraceListener.carrier(env).get("traceparent")).matches("00-0af7651916cd43dd8448eb211c80319c-[0-9a-f]{16}-01");
    }
}
