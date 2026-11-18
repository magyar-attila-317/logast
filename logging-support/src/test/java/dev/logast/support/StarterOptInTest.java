package dev.logast.support;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #93: the starter is added for whichever pack needs one of its classes, so on its own it switches nothing on. Each
 * behaviour a pack owns (log format, MDC hygiene, context propagation, batch trace) comes with its own property, which
 * logast writes for the pack that wants it.
 */
class StarterOptInTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(
            LoggingSupportAutoConfiguration.class, MdcHygieneConfiguration.class, TraceContextEnvConfiguration.class,
            ProblemTraceIdConfiguration.class));

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Bare {
    }

    @Test
    void theStarterAloneLeavesTheLogFormatAndBeansAlone() {
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(Bare.class).web(WebApplicationType.NONE)
                .properties("spring.application.name=plain-service", "management.otlp.metrics.export.enabled=false",
                        "management.tracing.export.otlp.enabled=false", "management.logging.export.otlp.enabled=false")
                .run()) {
            assertThat(ctx.getEnvironment().getProperty("logging.structured.format.console")).isNull();
            assertThat(ctx.getEnvironment().getPropertySources().contains(LoggingDefaultsEnvironmentPostProcessor.SOURCE)).isFalse();
            assertThat(ctx.getBeansOfType(TaskDecorator.class)).isEmpty();
            assertThat(ctx.getBeansOfType(BatchTraceListener.class)).isEmpty();
        }
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(TaskDecorator.class).doesNotHaveBean(BatchTraceListener.class)
                .doesNotHaveBean(FilterRegistrationBean.class).doesNotHaveBean(ProblemTraceId.class));
    }

    @Test
    void eachBehaviourComesWithItsOwnProperty() {
        runner.withPropertyValues("logging-support.context-propagation.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(TaskDecorator.class).doesNotHaveBean(BatchTraceListener.class)
                        .doesNotHaveBean(FilterRegistrationBean.class));
        runner.withPropertyValues("logging-support.batch-trace.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(BatchTraceListener.class).doesNotHaveBean(TaskDecorator.class)
                        .doesNotHaveBean(FilterRegistrationBean.class));
        runner.withPropertyValues("logging-support.mdc-hygiene.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(FilterRegistrationBean.class).doesNotHaveBean(TaskDecorator.class)
                        .doesNotHaveBean(BatchTraceListener.class));
        runner.withPropertyValues("logging-support.context-propagation.enabled=false", "logging-support.batch-trace.enabled=false",
                        "logging-support.mdc-hygiene.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(TaskDecorator.class).doesNotHaveBean(BatchTraceListener.class)
                        .doesNotHaveBean(FilterRegistrationBean.class));
    }

    @Test
    void logDefaultsOnlyWhenSwitchedOn() {
        assertThat(postProcessed(Map.of()).getProperty("logging.structured.format.console")).isNull();
        assertThat(postProcessed(Map.of(LoggingDefaultsEnvironmentPostProcessor.ENABLED, "false"))
                .getProperty("logging.structured.format.console")).isNull();
        StandardEnvironment on = postProcessed(Map.of(LoggingDefaultsEnvironmentPostProcessor.ENABLED, "true"));
        assertThat(on.getProperty("logging.structured.format.console")).isEqualTo("ecs");
        assertThat(postProcessed(Map.of(LoggingDefaultsEnvironmentPostProcessor.ENABLED, "true",
                "logging.structured.format.console", "logstash")).getProperty("logging.structured.format.console"))
                .as("a format the service sets wins").isEqualTo("logstash");
    }

    private static StandardEnvironment postProcessed(Map<String, Object> config) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("application", config));
        new LoggingDefaultsEnvironmentPostProcessor().postProcessEnvironment(env, null);
        return env;
    }
}
