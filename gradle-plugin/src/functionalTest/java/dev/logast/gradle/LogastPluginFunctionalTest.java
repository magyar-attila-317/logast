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
                .contains("2 new findings at or above medium")
                .contains("SWALLOWED_EXCEPTION  src/main/java/demo/PaymentController.java:13")
                .contains("ENTRYPOINT_SILENT  src/main/java/demo/PaymentController.java:10")
                .doesNotContain("SETUP_STRUCTURED_LOGGING");   // in the baseline
    }

    @Test
    void failOnSetsTheSeverityThatFails() throws IOException {
        gradle("logastBaseline").build();
        write("src/main/java/demo/PaymentController.java", PAYMENT_CONTROLLER);   // new findings are medium

        buildScript("logast { failOn = 'high' }\n");
        assertThat(gradle("logastCheck").build().getOutput()).contains("no new findings at or above high");

        buildScript("logast { failOn = 'low' }\n");
        assertThat(gradle("logastCheck").buildAndFail().getOutput()).contains("2 new findings at or above low");

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

    private GradleRunner gradle(String task) {
        return GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments(task, "--configuration-cache", "--stacktrace").forwardOutput();
    }
}
