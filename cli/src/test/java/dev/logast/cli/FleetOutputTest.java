package dev.logast.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** #196: a fleet run's lines on a terminal: other output clears the live line first; Gradle's failure in one line. */
class FleetOutputTest {
    @Test
    void otherOutputClearsTheLiveLineFirst() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream to = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        PrintStream err = new Out.Progress.ClearingStream(to);
        err.println("flyway: left out V2__x.sql");
        err.print("one ");
        err.println("line");
        assertThat(bytes.toString(StandardCharsets.UTF_8))
                .isEqualTo("\r\u001b[2Kflyway: left out V2__x.sql\n\r\u001b[2Kone line\n");
    }

    @Test
    void gradlesFailureInALine() {
        String output = """
                FAILURE: Build failed with an exception.

                * Where:
                Build file '/repos/client/build.gradle' line: 4

                * What went wrong:
                Plugin [id: 'io.spring.dependency-management', version: '1.0.8.RELEASE'] was not found in any of the following sources:

                - Gradle Core Plugins (plugin is not in 'org.gradle' namespace)
                """;
        assertThat(Main.Classpath.whatWentWrong(output))
                .isEqualTo("Plugin [id: 'io.spring.dependency-management', version: '1.0.8.RELEASE'] was not found in any of the following sources:");
    }
}
