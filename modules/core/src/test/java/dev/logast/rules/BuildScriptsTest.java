package dev.logast.rules;

import dev.logast.Analysis;
import dev.logast.Fixtures;
import dev.logast.orders.Edit;
import dev.logast.orders.WorkOrder;
import dev.logast.policy.Policy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #80: dependencies declared in a script the build file applies (apply from:) are seen by the presence checks, and new
 * ones go into the script that holds the top-level dependencies block, in that script's DSL. The Groovy case runs end
 * to end in E2eGoldenTest.dependenciesGoIntoTheScriptTheBuildFileApplies.
 */
class BuildScriptsTest {
    /** Spring Boot 3 by its plugin; the fixture classpath is Boot 4, so only the build scripts say what is declared. */
    private static final String GROOVY_PLUGINS = "plugins {\n    id 'java'\n    id 'org.springframework.boot' version '3.5.7'\n}\n\n";
    private static final String KOTLIN_PLUGINS = "plugins {\n    java\n    id(\"org.springframework.boot\") version \"3.5.7\"\n}\n\n";

    @Test
    void otelDependenciesInTheAppliedScriptAreNotAddedAgain(@TempDir Path dir) throws IOException {
        write(dir, "logast.yaml", "support:\n  mode: dependency\n");   // the starter as a library (#122: vendor is the default)
        write(dir, "build.gradle", GROOVY_PLUGINS + "apply from: 'gradle/dependencies.gradle'\n");
        write(dir, "gradle/dependencies.gradle", """
                dependencies {
                    implementation 'org.springframework.boot:spring-boot-starter-actuator'
                    implementation 'io.micrometer:micrometer-tracing-bridge-otel'
                    implementation 'io.opentelemetry:opentelemetry-exporter-otlp'
                }
                """);
        List<WorkOrder> orders = scan(dir);
        assertThat(order(orders, Rules.SETUP_OTEL_STARTER)).as("all three are declared in the applied script").isEmpty();

        // the logging-support starter is missing: it goes into the applied script, in Groovy
        WorkOrder support = order(orders, Rules.SETUP_SUPPORT_STARTER).orElseThrow();
        Edit edit = support.edits().getFirst();
        assertThat(edit.file()).isEqualTo("gradle/dependencies.gradle");
        assertThat(edit.text()).startsWith("    implementation '").endsWith("'\n");
        assertThat(support.file()).isEqualTo("gradle/dependencies.gradle");
        assertThat(support.line()).isEqualTo(2);
    }

    @Test
    void kotlinScriptsAreFollowedOnceAndEditedInKotlin(@TempDir Path dir) throws IOException {
        write(dir, "build.gradle.kts", KOTLIN_PLUGINS + "apply(from = \"gradle/dependencies.gradle.kts\")\n");
        // applies itself: read once, no endless recursion
        write(dir, "gradle/dependencies.gradle.kts", """
                apply(from = "gradle/dependencies.gradle.kts")

                dependencies {
                    implementation("org.springframework.boot:spring-boot-starter-actuator")
                }
                """);
        Edit edit = otelEdit(dir);
        assertThat(edit.file()).isEqualTo("gradle/dependencies.gradle.kts");
        assertThat(edit.text()).isEqualTo("    implementation(\"io.micrometer:micrometer-tracing-bridge-otel\")\n"
                + "    implementation(\"io.opentelemetry:opentelemetry-exporter-otlp\")\n");
    }

    @Test
    void theTargetScriptDecidesTheDsl(@TempDir Path dir) throws IOException {
        // a Kotlin build file applying a Groovy script: the lines are Groovy
        write(dir, "build.gradle.kts", KOTLIN_PLUGINS + "apply(from = \"gradle/dependencies.gradle\")\n");
        write(dir, "gradle/dependencies.gradle", "dependencies {\n    implementation 'org.springframework.boot:spring-boot-starter-actuator'\n}\n");
        Edit edit = otelEdit(dir);
        assertThat(edit.file()).isEqualTo("gradle/dependencies.gradle");
        assertThat(edit.text()).isEqualTo("    implementation 'io.micrometer:micrometer-tracing-bridge-otel'\n"
                + "    implementation 'io.opentelemetry:opentelemetry-exporter-otlp'\n");
    }

    @Test
    void theBuildFilesOwnBlockComesFirst(@TempDir Path dir) throws IOException {
        write(dir, "build.gradle", GROOVY_PLUGINS + "apply from: 'gradle/dependencies.gradle'\n\ndependencies {\n}\n");
        write(dir, "gradle/dependencies.gradle", "dependencies {\n    implementation 'io.micrometer:micrometer-tracing-bridge-otel'\n}\n");
        Edit edit = otelEdit(dir);
        assertThat(edit.file()).isEqualTo("build.gradle");
        assertThat(edit.text()).as("what the applied script declares is not added")
                .doesNotContain("micrometer-tracing-bridge-otel").contains("spring-boot-starter-actuator");
    }

    /**
     * #270: with the starter Boot continues callers' traces, which services behind one gateway want (the default); a
     * public-facing service distrusts them through the policy, unless it already configures propagation itself.
     */
    @Test
    void inboundTraceContextIsDistrustedOnlyWhenThePolicySaysSo(@TempDir Path dir) throws IOException {
        write(dir, "build.gradle", GROOVY_PLUGINS + "dependencies {\n}\n");
        assertThat(otelEdit(dir).file()).isEqualTo("build.gradle");
        WorkOrder trusting = order(scan(dir), Rules.SETUP_OTEL_STARTER).orElseThrow();
        assertThat(trusting.edits()).as("by default callers' traces continue: Boot's propagation defaults stay")
                .noneMatch(e -> e.file().endsWith("application.yml") && e.text().contains("propagation"));
        write(dir, "logast.yaml", "packs: [tracing]\nentrypoints:\n  trustInboundTrace: false\n");
        WorkOrder distrusting = order(scan(dir), Rules.SETUP_OTEL_STARTER).orElseThrow();
        assertThat(distrusting.edits()).filteredOn(e -> e.file().endsWith("application.yml")).singleElement()
                .satisfies(e -> assertThat(e.text()).contains("consume: []", "produce: [w3c]", "baggage:\n      enabled: false"));
        write(dir, "logast.yaml", "packs: [tracing]\nentrypoints:\n  trustInboundTrace: false\n");
        write(dir, "src/main/resources/application.yml", "management:\n  tracing:\n    propagation:\n      type: w3c\n");
        WorkOrder configured = order(scan(dir), Rules.SETUP_OTEL_STARTER).orElseThrow();
        assertThat(configured.edits()).as("the service's own propagation settings stay as they are")
                .noneMatch(e -> e.file().endsWith("application.yml"));
    }

    @Test
    void computedOrRemoteTargetsAreReported(@TempDir Path dir) throws IOException {
        write(dir, "build.gradle", GROOVY_PLUGINS + "apply from: \"$rootDir/gradle/dependencies.gradle\"\n"
                + "apply from: 'https://example.org/common.gradle'\n");
        write(dir, "gradle/dependencies.gradle", "dependencies {\n}\n");
        WorkOrder otel = order(scan(dir), Rules.SETUP_OTEL_STARTER).orElseThrow();
        assertThat(otel.edits()).isEmpty();
        assertThat(otel.suggestion()).contains("add them manually")
                .contains("\"$rootDir/gradle/dependencies.gradle\"").contains("'https://example.org/common.gradle'");
    }

    private static Edit otelEdit(Path dir) {
        WorkOrder otel = order(scan(dir), Rules.SETUP_OTEL_STARTER).orElseThrow();
        // one build-file edit (an application.yml merge may go with it)
        List<Edit> build = otel.edits().stream().filter(e -> !e.file().endsWith("application.yml")).toList();
        assertThat(build).hasSize(1);
        return build.getFirst();
    }

    private static Optional<WorkOrder> order(List<WorkOrder> orders, String rule) {
        return orders.stream().filter(o -> o.rule().equals(rule)).findFirst();
    }

    private static List<WorkOrder> scan(Path dir) {
        try {
            write(dir, "src/main/java/x/Parcels.java", "package x;\n\npublic class Parcels {\n}\n");
            return Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null))).orders();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void write(Path dir, String rel, String text) throws IOException {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }
}
