package dev.logast.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #115: a sample service build applies {@code dev.logast}; the tasks run logast on it with the build's own compile
 * classpath. The sample compiles against the fixture's Spring jars (the e2e classpath), so TestKit downloads nothing.
 */
class LogastPluginFunctionalTest {
    @TempDir
    Path project;

    /** A controller that logs: its only finding is the service-wide SETUP_STRUCTURED_LOGGING (medium). */
    static final String ORDER_CONTROLLER = """
            package demo;

            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.PathVariable;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            public class OrderController {
                private static final Logger log = LoggerFactory.getLogger(OrderController.class);

                @GetMapping("/orders/{orderId}")
                public String get(@PathVariable String orderId) {
                    log.info("loading order");
                    return orderId;
                }
            }
            """;

    /** Added after the baseline: a silent entrypoint that swallows an exception (two new medium findings). */
    static final String PAYMENT_CONTROLLER = """
            package demo;

            import org.springframework.web.bind.annotation.PostMapping;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            public class PaymentController {

                @PostMapping("/payments")
                public String pay(String paymentId) {
                    try {
                        return Integer.toString(Integer.parseInt(paymentId));
                    } catch (RuntimeException e) {
                        return "failed";
                    }
                }
            }
            """;

    @BeforeEach
    void sampleBuild() throws IOException {
        List<String> jars = Files.readAllLines(Path.of(System.getProperty("logast.fixture.classpath"))).stream()
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'sample'\n");
        buildScript("");
        write("src/main/java/demo/OrderController.java", ORDER_CONTROLLER);
        // the jars as the build's implementation dependencies: logast must get them from compileClasspath
        Files.writeString(project.resolve("deps.gradle"), "dependencies {\n    implementation files(\n"
                + jars.stream().map(j -> "        '" + j.replace("\\", "/") + "'").collect(Collectors.joining(",\n"))
                + "\n    )\n}\n");
    }

    @Test
    void scanWritesTheOrdersWithTheBuildsClasspath() throws IOException {
        BuildResult r = gradle("logastScan").build();

        assertThat(r.task(":logastScan").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        Path orders = project.resolve(".logast/reports/orders.json");
        assertThat(orders).exists();
        assertThat(Files.readString(orders)).contains("SETUP_STRUCTURED_LOGGING");
        // the build's compileClasspath reached logast: without it the analysis is marked incomplete
        assertThat(Files.readString(orders)).containsPattern("\"noClasspath\"\\s*:\\s*false");
    }

    @Test
    void sarifWritesEveryFindingForCodeScanning() throws IOException {
        write("src/main/java/demo/PaymentController.java", PAYMENT_CONTROLLER);
        BuildResult r = gradle("logastSarif").build();   // findings do not fail it: the gate is logastCheck

        assertThat(r.task(":logastSarif").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        String sarif = Files.readString(project.resolve("build/reports/logast/logast.sarif")).strip();
        // only the SARIF document: nothing else logast prints may end up in the file GitHub parses
        assertThat(sarif).startsWith("{").endsWith("}").contains("\"2.1.0\"")
                .contains("\"ruleId\" : \"SWALLOWED_EXCEPTION\"").contains("\"ruleId\" : \"SETUP_STRUCTURED_LOGGING\"")
                .contains("src/main/java/demo/PaymentController.java");
    }

    @Test
    void checkPassesOnTheBaselineAndFailsOnANewFinding() throws IOException {
        gradle("logastBaseline").build();
        assertThat(project.resolve(".logast/baseline.json")).exists();

        BuildResult pass = gradle("logastCheck").build();
        assertThat(pass.task(":logastCheck").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(pass.getOutput()).contains("no new findings at or above medium");

        write("src/main/java/demo/PaymentController.java", PAYMENT_CONTROLLER);
        BuildResult fail = gradle("logastCheck").buildAndFail();
        assertThat(fail.task(":logastCheck").getOutcome()).isEqualTo(TaskOutcome.FAILED);
        assertThat(fail.getOutput())
                .contains("1 new finding at or above medium")
                .contains("SWALLOWED_EXCEPTION  src/main/java/demo/PaymentController.java:13")
                // #285: a POST endpoint with no known rate gets no started line: the access log records it
                .doesNotContain("ENTRYPOINT_SILENT")
                .doesNotContain("SETUP_STRUCTURED_LOGGING");   // in the baseline
    }

    @Test
    void failOnSetsTheSeverityThatFails() throws IOException {
        gradle("logastBaseline").build();
        write("src/main/java/demo/PaymentController.java", PAYMENT_CONTROLLER);   // new findings are medium

        buildScript("logast { failOn = 'high' }\n");
        assertThat(gradle("logastCheck").build().getOutput()).contains("no new findings at or above high");

        buildScript("logast { failOn = 'low' }\n");
        assertThat(gradle("logastCheck").buildAndFail().getOutput()).contains("1 new finding at or above low");

        buildScript("logast { failOn = 'severe' }\n");
        assertThat(gradle("logastCheck").buildAndFail().getOutput()).contains("logast.failOn = \"severe\": use one of [low, medium, high]");
    }

    @Test
    void checkWithoutABaselineFailsAndSaysHowToRecordIt() throws IOException {
        buildScript("logast { baseline = file('ci/logast-baseline.json') }\n");

        BuildResult r = gradle("logastCheck").buildAndFail();
        assertThat(r.getOutput()).contains("no baseline at ").contains("ci/logast-baseline.json")
                .contains("./gradlew logastBaseline");

        gradle("logastBaseline").build();
        assertThat(project.resolve("ci/logast-baseline.json")).exists();
        assertThat(gradle("logastCheck").build().task(":logastCheck").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
    }

    /**
     * What logastApply needs no text for on the sample: the logging setup and the order id in MDC. Vendor mode: the
     * support classes are copied into the service (dependency mode adds a library this sample build cannot resolve).
     */
    static final String APPLY_POLICY = """
            support:
              mode: vendor
            vocabulary:
              order:
                mdc:
                  key: orderId
                  sources:
                    params: [orderId]
            """;

    @Test
    void applyMigratesTheSourcesAndTheBuildStillCompiles() throws IOException {   // #122 step 4
        write("logast.yaml", APPLY_POLICY);
        Path order = project.resolve("src/main/java/demo/OrderController.java");

        BuildResult r = gradle("logastApply").build();
        assertThat(r.task(":logastApply").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        // the controller carries the order id in MDC now
        assertThat(Files.readString(order)).contains("MdcScope.of(\"orderId\", orderId)");
        assertThat(r.getOutput()).containsPattern("\\d+ changes? in \\d+ files?").doesNotContain("DRY RUN").doesNotContain("REJECTED")
                .doesNotContain("CHECK FAILED");

        assertThat(gradle("compileJava").build().task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
    }

    /**
     * Apply adds a dependency (dependency mode; vendor is the default since #122): the CLI refreshes the classpath by running the service's
     * Gradle, which must not happen inside the build (the build's classpath came with --classpath-file). The stand-in
     * gradlew would leave a marker; apply says to run logastApply again instead.
     */
    @Test
    void applyInsideTheBuildRunsNoNestedGradle() throws IOException {
        Path gradlew = project.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\ntouch nested-gradle-ran\n");
        assertThat(gradlew.toFile().setExecutable(true)).isTrue();
        write("logast.yaml", "support:\n  mode: dependency\n");

        BuildResult r = gradle("logastApply").build();
        assertThat(Files.readString(project.resolve("deps.gradle"))).contains("dev.logast:logging-support");
        assertThat(project.resolve("nested-gradle-ran")).doesNotExist();
        assertThat(r.getOutput()).contains("apply again (./gradlew logastApply)");
    }

    @Test
    void applyPreviewLeavesTheSourcesUnchanged() throws IOException {
        write("logast.yaml", APPLY_POLICY);
        Path order = project.resolve("src/main/java/demo/OrderController.java");

        BuildResult option = gradle("logastApply", "--preview").build();
        assertThat(option.getOutput()).contains("DRY RUN").contains("would apply ").contains("OrderController.java");
        assertThat(Files.readString(order)).isEqualTo(ORDER_CONTROLLER);

        gradle("logastApply", "-PlogastDryRun").build();
        assertThat(Files.readString(order)).isEqualTo(ORDER_CONTROLLER);
        assertThat(project.resolve("src/main/resources/application.yml")).doesNotExist();
    }

    private void buildScript(String logastBlock) throws IOException {
        Files.writeString(project.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'dev.logast'
                }
                apply from: 'deps.gradle'
                """ + logastBlock);
    }

    private void write(String path, String content) throws IOException {
        Path file = project.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private GradleRunner gradle(String... args) {
        List<String> all = new java.util.ArrayList<>(List.of(args));
        all.addAll(List.of("--configuration-cache", "--stacktrace"));
        return GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments(all).forwardOutput();
    }
}
