package dev.logast.support;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Brings {@link ProblemTraceId} (Spring MVC) and {@link ReactiveProblemTraceId} (WebFlux) in with the starter (its own conditions decide whether it is on). A copied version is
 * found by component scan instead, so this class is not copied.
 */
@AutoConfiguration(afterName = "org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration")
@Import({ProblemTraceId.class, ReactiveProblemTraceId.class})
public class ProblemTraceIdConfiguration {
}
