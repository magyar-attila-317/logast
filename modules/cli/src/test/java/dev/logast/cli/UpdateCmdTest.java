package dev.logast.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code logast update}: run from a build (no install folder with install.sh), it says how to install instead. */
class UpdateCmdTest {
    @Test
    void withoutAnInstallItSaysHowToInstall() {
        PrintStream err = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(buf, true, StandardCharsets.UTF_8));
            int code = new CommandLine(new Main()).execute("update");
            assertThat(code).isEqualTo(2);
        } finally {
            System.setErr(err);
        }
        assertThat(buf.toString(StandardCharsets.UTF_8)).contains("install.sh").contains("curl -fsSL");
        assertThat(UpdateCmd.home()).as("classes, not lib/*.jar").isNull();
    }
}
