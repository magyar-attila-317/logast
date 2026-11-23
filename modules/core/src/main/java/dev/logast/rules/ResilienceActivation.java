package dev.logast.rules;

import dev.logast.graph.CodeIndex;
import dev.logast.model.Annotations;
import dev.logast.parse.ClasspathProbe;

import java.util.List;

/**
 * Whether the resilience annotations a service uses actually run (#90, #255): an annotation Spring does not process is
 * metadata, and the method runs once. Shared by RETRY_INACTIVE and the export's call protection.
 * <ul>
 *   <li>Spring Framework 7's {@code @Retryable} / {@code @ConcurrencyLimit}: {@code @EnableResilientMethods}, or a
 *       RetryAnnotationBeanPostProcessor / ConcurrencyLimitBeanPostProcessor bean (Framework reference, "Resilience
 *       Features"); Spring Boot does not enable them.</li>
 *   <li>Spring Retry's {@code @Retryable} / {@code @CircuitBreaker}: {@code @EnableRetry} (spring-retry README).</li>
 *   <li>Resilience4j's {@code @Retry}, {@code @CircuitBreaker}, {@code @Bulkhead}, {@code @TimeLimiter}: their aspects,
 *       from the Spring Boot starter's auto-configuration or declared by hand, and AspectJ for Spring AOP
 *       (Resilience4j docs, "Getting Started" with Spring Boot: the starter and spring-boot-starter-aop).</li>
 * </ul>
 */
public final class ResilienceActivation {
    public static final String RESILIENCE_PKG = "org.springframework.resilience.annotation.";
    public static final String SPRING_RETRY_PKG = "org.springframework.retry.annotation.";
    public static final String ENABLE_RESILIENT = RESILIENCE_PKG + "EnableResilientMethods";
    public static final String ENABLE_RETRY = SPRING_RETRY_PKG + "EnableRetry";

    private static final List<String> RESILIENCE4J_AUTOCONFIG = List.of(
            "io.github.resilience4j.springboot3.retry.autoconfigure.RetryAutoConfiguration",
            "io.github.resilience4j.springboot.retry.autoconfigure.RetryAutoConfiguration",
            "io.github.resilience4j.retry.autoconfigure.RetryAutoConfiguration");
    private static final String ASPECTJ = "org.aspectj.weaver.tools.PointcutParser";

    private ResilienceActivation() {
    }

    /** Spring Framework 7's {@code @Retryable} runs. */
    public static boolean springRetryableOn(CodeIndex index) {
        return annotatedAnywhere(index, ENABLE_RESILIENT) || mentioned(index, "RetryAnnotationBeanPostProcessor");
    }

    /** Spring Framework 7's {@code @ConcurrencyLimit} runs. */
    public static boolean concurrencyLimitOn(CodeIndex index) {
        return annotatedAnywhere(index, ENABLE_RESILIENT) || mentioned(index, "ConcurrencyLimitBeanPostProcessor");
    }

    /** Spring Retry's {@code @Retryable} / {@code @CircuitBreaker} run. */
    public static boolean springRetryOn(CodeIndex index) {
        return annotatedAnywhere(index, ENABLE_RETRY);
    }

    /** Resilience4j's annotations run: its aspects are there (the starter, or declared by hand) and AspectJ is. */
    public static boolean resilience4jOn(CodeIndex index, ClasspathProbe probe) {
        boolean aspects = RESILIENCE4J_AUTOCONFIG.stream().anyMatch(probe::hasClass) || mentioned(index, "RetryAspect")
                || mentioned(index, "CircuitBreakerAspect") || mentioned(index, "BulkheadAspect");
        return aspects && probe.hasClass(ASPECTJ);
    }

    /** Whether the annotation {@code fqn} on a method runs, or null when it is no resilience annotation logast knows. */
    public static Boolean on(String fqn, CodeIndex index, ClasspathProbe probe) {
        if (fqn.equals(RESILIENCE_PKG + "Retryable")) {
            return springRetryableOn(index);
        }
        if (fqn.equals(RESILIENCE_PKG + "ConcurrencyLimit")) {
            return concurrencyLimitOn(index);
        }
        if (fqn.startsWith(SPRING_RETRY_PKG)) {
            return springRetryOn(index);
        }
        if (fqn.startsWith("io.github.resilience4j.")) {
            return resilience4jOn(index, probe);
        }
        return null;
    }

    static boolean annotatedAnywhere(CodeIndex index, String fqn) {
        return index.classes().stream().anyMatch(c -> c.decl().getLeadingAnnotations().stream().anyMatch(a -> Annotations.is(a, fqn)));
    }

    /** Some source file of the service names {@code word} (a bean type declared by hand). */
    static boolean mentioned(CodeIndex index, String word) {
        return index.classes().stream().anyMatch(c -> c.file().text().contains(word));
    }
}
