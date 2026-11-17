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
        assertThat(otel.edits()).hasSize(1);
        return otel.edits().getFirst();
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
