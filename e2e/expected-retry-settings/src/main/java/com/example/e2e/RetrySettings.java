package com.example.e2e;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retry settings of the service's @Retryable methods (application.yml, resilience.retry.*). The annotations read
 * the same keys through placeholders, so a changed value takes effect on restart, without a rebuild; this class
 * makes them typed and lists them in one place.
 *
 * @param defaults            what Spring Framework's @Retryable usages share
 * @param springRetryDefaults what Spring Retry's @Retryable usages share
 * @param usages              usages with settings of their own: class, then method (kebab case); which ones is
 *                            decided in .logast/decisions/retry.yaml
 */
@ConfigurationProperties("resilience.retry")
public record RetrySettings(Retry defaults, Retry springRetryDefaults, Map<String, Map<String, Retry>> usages) {
    /**
     * One block of settings.
     *
     * @param maxRetries  Spring Framework: retries after the first call
     * @param maxAttempts Spring Retry: calls in all, the first one included
     * @param delay       the delay before the first retry (Spring Retry: plain milliseconds)
     * @param jitter      Spring Framework: a random part added to or taken from each delay
     * @param multiplier  each delay is the previous one times this
     * @param maxDelay    the longest delay (Spring Retry: plain milliseconds)
     * @param timeout     Spring Framework: the longest time all attempts together may take
     */
    public record Retry(Long maxRetries, Integer maxAttempts, Duration delay, Duration jitter, Double multiplier,
                        Duration maxDelay, Duration timeout) {
    }
}
