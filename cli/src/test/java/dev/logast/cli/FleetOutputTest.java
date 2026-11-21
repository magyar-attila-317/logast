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

    /** #206: a repo line's columns line up whatever the name and detail: status, name, time, time left, then the detail. */
    @Test
    void repoLinesKeepTheirColumns() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream before = System.err;
        System.setErr(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        try {
            FleetLog log = new FleetLog();
            log.intro("fleet", java.util.List.of(), 12);
            FleetLog.Phase p = log.phase("scan", null, 12);
            p.done("billing", FleetLog.Status.OK, 46_000_000_000L, "scanned", java.util.List.of());
            p.done("a-very-long-repository-name-indeed", FleetLog.Status.PROBLEM, 2_000_000_000L, "scanned", java.util.List.of());
            p.done("ui", FleetLog.Status.FAILED, 1_000_000_000L, "word ".repeat(60).strip(), java.util.List.of());
        } finally {
            System.setErr(before);
            Out.Progress.quiet = false;   // intro() quiets the live lines for the run: not for the tests after this one
        }
        java.util.List<String> lines = bytes.toString(StandardCharsets.UTF_8).lines().filter(l -> l.contains("/12]") || l.startsWith(" ".repeat(20)))
                .toList();
        assertThat(lines.get(0)).contains("billing     ");
        assertThat(lines.get(1)).contains("a-very-long…").doesNotContain("indeed");
        int detail = lines.get(0).indexOf("scanned");
        assertThat(lines.get(1).indexOf("scanned")).isEqualTo(detail);
        assertThat(lines.get(2).indexOf("word")).isEqualTo(detail);
        assertThat(lines.get(3).indexOf("word")).isEqualTo(detail);   // a long detail goes on under its column
        assertThat(lines).allMatch(l -> l.length() <= Out.width());
    }
}
