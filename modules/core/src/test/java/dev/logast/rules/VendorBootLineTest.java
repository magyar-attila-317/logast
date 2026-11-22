package dev.logast.rules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #116 (found by the public corpus): the vendored LoggingDefaultsEnvironmentPostProcessor and its spring.factories key
 * follow the service's Spring Boot line: Boot 4 moved EnvironmentPostProcessor out of org.springframework.boot.env.
 */
class VendorBootLineTest {
    @Test
    void theInterfaceOfTheServicesBootLine() {
        assertThat(SetupRules.environmentPostProcessor(false, true)).as("Boot 3").isEqualTo("org.springframework.boot.env.EnvironmentPostProcessor");
        assertThat(SetupRules.environmentPostProcessor(true, true)).as("Boot 4 (keeps the deprecated old one)")
                .isEqualTo("org.springframework.boot.EnvironmentPostProcessor");
        assertThat(SetupRules.environmentPostProcessor(false, false)).as("unknown: the current line")
                .isEqualTo("org.springframework.boot.EnvironmentPostProcessor");
    }

    @Test
    void theCopiedSourceImportsTheBoot4InterfaceThatBoot3Replaces() {
        assertThat(SetupRules.vendoredSource("LoggingDefaultsEnvironmentPostProcessor", "x.logging"))
                .as("the line the Boot 3 copy rewrites").contains("import org.springframework.boot.EnvironmentPostProcessor;");
    }
}
