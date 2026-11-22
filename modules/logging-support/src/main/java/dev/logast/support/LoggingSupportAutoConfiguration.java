package dev.logast.support;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.task.TaskDecorator;

/**
 * Registers the MDC/trace propagating {@link TaskDecorator} (picked up by Spring Boot's task executors, so
 * {@code @Async} and injected executors keep context), publishes the ObservationRegistry for
 * {@link ObservedContext}, and traces batch runs.
 * <p>The decorator and the batch trace change behaviour, so each is off until its property is {@code true}
 * ({@code logging-support.context-propagation.enabled}, {@code logging-support.batch-trace.enabled}): the starter
 * alone changes nothing; logast writes the property for the pack that uses the feature.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration"})
public class LoggingSupportAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(TaskDecorator.class)
    @ConditionalOnProperty(name = "logging-support.context-propagation.enabled", havingValue = "true")
    public TaskDecorator contextPropagatingTaskDecorator() {
        return ContextPropagation::wrap;
    }

    @Bean
    public SmartInitializingSingleton observationRegistryHolderInitializer(ObjectProvider<ObservationRegistry> registry) {
        return () -> ObservationRegistryHolder.set(registry.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    @Bean
    @ConditionalOnProperty(name = "logging-support.batch-trace.enabled", havingValue = "true")
    public BatchTraceListener batchTraceListener(ObjectProvider<ObservationRegistry> registry, Environment environment) {
        return new BatchTraceListener(registry.getIfAvailable(() -> ObservationRegistry.NOOP), environment);
    }
}
