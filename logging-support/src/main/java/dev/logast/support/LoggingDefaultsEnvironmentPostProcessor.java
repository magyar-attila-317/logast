package dev.logast.support;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Lowest-precedence defaults: ECS JSON console logs whose {@code service.name}/{@code service.version} come from
 * the {@code SERVICE_NAME}/{@code APP_VERSION} environment variables, falling back to {@code spring.application.name}
 * and {@code spring.application.version}. Anything set in application.yml or the environment wins.
 */
public final class LoggingDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {
    static final String SOURCE = "logging-support-defaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(SOURCE)) {
            return;
        }
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE, Map.of(
                "logging.structured.format.console", "ecs",
                "logging.structured.ecs.service.name", "${SERVICE_NAME:${spring.application.name:application}}",
                "logging.structured.ecs.service.version", "${APP_VERSION:${spring.application.version:unknown}}")));
    }
}
