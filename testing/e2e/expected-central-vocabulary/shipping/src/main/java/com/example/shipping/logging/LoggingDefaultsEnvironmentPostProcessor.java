// Copied by logast (support.mode: vendor) from logging-support. Keep in sync or switch to the starter.
// Source: logast, https://github.com/magyar-attila-317/logast (Apache License 2.0)
package com.example.shipping.logging;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Lowest-precedence defaults: ECS JSON console logs whose {@code service.name}/{@code service.version} come from
 * the {@code SERVICE_NAME}/{@code APP_VERSION} environment variables, falling back to {@code spring.application.name}
 * and {@code spring.application.version}. Anything set in application.yml or the environment wins.
 * <p>Only with {@code logging-support.log-defaults.enabled=true} (logast's logging pack writes it): the log format
 * belongs to the service, so the starter alone does not change it. Runs after the config data is loaded (no order
 * set, so after Spring Boot's ConfigDataEnvironmentPostProcessor), so application.yml can switch it on.
 */
public final class LoggingDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {
    static final String SOURCE = "logging-support-defaults";
    static final String ENABLED = "logging-support.log-defaults.enabled";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(SOURCE) || !"true".equalsIgnoreCase(environment.getProperty(ENABLED))) {
            return;
        }
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE, Map.of(
                "logging.structured.format.console", "ecs",
                "logging.structured.ecs.service.name", "${SERVICE_NAME:${spring.application.name:application}}",
                "logging.structured.ecs.service.version", "${APP_VERSION:${spring.application.version:unknown}}")));
    }
}
