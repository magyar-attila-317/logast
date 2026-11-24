package dev.logast.cli;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** #288: "nothing", "partial" and "all" are told apart by the exit code, not only by reading the output. */
class ApplyExitCodeTest {
    @Test
    void rejectedChangesOrFailedChecksAreNotASuccess() {
        assertThat(Main.Apply.exitCode(Map.of(), List.of())).isZero();
        assertThat(Main.Apply.exitCode(Map.of("MCA-1a2b3c", "does not compile"), List.of())).isEqualTo(1);
        assertThat(Main.Apply.exitCode(Map.of(), List.of("src/A.java:3 a key both in MDC and in the message"))).isEqualTo(1);
    }
}
