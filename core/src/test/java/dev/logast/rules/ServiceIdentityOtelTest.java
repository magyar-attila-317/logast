package dev.logast.rules;

import dev.logast.Analysis;
import dev.logast.orders.WorkOrder;
import dev.logast.policy.Policy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #117 (found by the public corpus): SETUP_OTEL_STARTER adds the OpenTelemetry starter in the same pass as
 * SETUP_SERVICE_IDENTITY runs, but the classpath logast reads has it only after apply refreshes it. Identity must count
 * a starter the build declares, or the next apply adds the resource attributes (the migration does not settle).
 */
class ServiceIdentityOtelTest {
    @TempDir
    Path tmp;

    private List<String> identityEdits(String dependencies) throws IOException {
        Path root = tmp.resolve("p" + dependencies.hashCode());
        Files.createDirectories(root.resolve("src/main/java/x"));
        Files.createDirectories(root.resolve("src/main/resources"));
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }\ndependencies {\n" + dependencies + "}\n");
        Files.writeString(root.resolve("src/main/resources/application.yml"), "spring:\n  application:\n    name: ledger\n"
                + "    version: 1.0\n");
        // the identity rule needs the @SpringBootApplication class; without a classpath, a stand-in of the annotation
        Path boot = Files.createDirectories(root.resolve("src/main/java/org/springframework/boot/autoconfigure"));
        Files.writeString(boot.resolve("SpringBootApplication.java"),
                "package org.springframework.boot.autoconfigure;\n\npublic @interface SpringBootApplication {\n}\n");
        Files.writeString(root.resolve("src/main/java/x/LedgerApp.java"), "package x;\n\n"
                + "import org.springframework.boot.autoconfigure.SpringBootApplication;\n\n@SpringBootApplication\nclass LedgerApp {\n}\n");
        Files.writeString(root.resolve("logast.yaml"), "packs: [logging, tracing]\n");
        // no classpath: OpenTelemetry is not on it, only (maybe) declared in the build
        List<WorkOrder> orders = Rules.scan(Analysis.run(root, List.of(), Policy.resolve(root, null))).orders();
        return orders.stream().filter(o -> o.rule().equals(Rules.SETUP_SERVICE_IDENTITY))
                .flatMap(o -> o.edits().stream()).map(e -> e.text()).toList();
    }

    @Test
    void aDeclaredOpenTelemetryStarterGetsItsResourceAttributesInTheSamePass() throws IOException {
        assertThat(String.join("\n", identityEdits("    implementation 'org.springframework.boot:spring-boot-starter-opentelemetry'\n")))
                .contains("opentelemetry:\n    resource-attributes:\n      service:\n        version: ${spring.application.version}");
        assertThat(String.join("\n", identityEdits("    implementation 'org.springframework.boot:spring-boot-starter-web'\n")))
                .as("no OpenTelemetry declared or on the classpath").doesNotContain("resource-attributes");
    }
}
