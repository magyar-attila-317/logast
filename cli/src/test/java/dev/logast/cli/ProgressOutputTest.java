package dev.logast.cli;

import dev.logast.orders.Ansi;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ProgressOutputTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void keepsEachProgressStepOnItsOwnLine(boolean terminal) {
        boolean previousAnsi = Ansi.enabled();
        PrintStream previousErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintStream err = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            Ansi.enable(terminal);
            System.setErr(err);
            try (Out.Progress progress = new Out.Progress("first phase")) {
                progress.update("second phase");
            }
        } finally {
            System.setErr(previousErr);
            Ansi.enable(previousAnsi);
        }

        String output = captured.toString(StandardCharsets.UTF_8).replaceAll("\\u001b\\[[0-9;]*m", "");
        assertThat(output).containsSubsequence("… first phase\n", "… second phase\n");
    }
}
