package dev.logast.cli;

import com.fasterxml.jackson.databind.JsonNode;
import dev.logast.orders.OrderJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** CI gate of `logast scan --fail-on` (#55): a degraded analysis (unparsed files, no classpath) must not pass. */
class ScanGateTest {
    @TempDir
    Path tmp;

    record Run(int exit, String out, String err) {
    }

    private Path project(boolean broken) throws IOException {
        Path app = tmp.resolve("app");
        Path pkg = app.resolve("src/main/java/demo");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Ok.java"), "package demo;\n\nclass Ok {\n    int one() {\n        return 1;\n    }\n}\n");
        if (broken) {
            Files.writeString(pkg.resolve("Broken.java"), "package demo;\n\nclass Broken {\n    void x( {\n}\n");
        }
        return app;
    }

    /** Some jar of the test JVM: a non-empty classpath, so only the parse error degrades the analysis. */
    private Path classpathFile() throws IOException {
        String jar = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p -> p.endsWith(".jar")).findFirst().orElseThrow();
        Path f = tmp.resolve("classpath.txt");
        Files.writeString(f, jar + "\n");
        return f;
    }

    private static Run run(String... args) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int rc;
        try {
            System.setOut(new PrintStream(o, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(e, true, StandardCharsets.UTF_8));
            rc = new CommandLine(new Main()).execute(args);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        return new Run(rc, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8).replaceAll("\u001b\\[[0-9;]*m", ""));
    }

    @Test
    void completeAnalysisWithoutFindingsPasses() throws IOException {
        Path app = project(false);
        Run r = run("scan", app.toString(), "--fail-on", "high", "--format", "json", "--classpath-file", classpathFile().toString());
        assertThat(r.exit()).as(r.err()).isZero();
    }

    /** A baseline recorded from an incomplete analysis hides what was not analysed: say so when recording it. */
    @Test
    void baselineFromAnIncompleteAnalysisIsFlagged() throws IOException {
        Path app = project(true);
        Run r = run("scan", app.toString(), "--update-baseline", "--classpath-file", classpathFile().toString());
        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.err()).contains("analysis incomplete").contains("Broken.java").contains("baseline");
    }

    @Test
    void unparsableFileFailsTheGateInEveryFormat() throws IOException {
        Path app = project(true);
        String cp = classpathFile().toString();
        for (String format : new String[]{"json", "sarif", "summary", "compact"}) {
            Run r = run("scan", app.toString(), "--fail-on", "high", "--format", format, "--classpath-file", cp);
            assertThat(r.exit()).as(format + ": " + r.err()).isEqualTo(4);
            assertThat(r.err()).as(format).contains("analysis incomplete").contains("Broken.java");
        }
        // without --fail-on the scan still reports and exits 0 (not a CI gate)
        assertThat(run("scan", app.toString(), "--format", "json", "--classpath-file", cp).exit()).isZero();
    }

    @Test
    void sarifSaysTheRunWasIncomplete() throws IOException {
        Path app = project(true);
        Run r = run("scan", app.toString(), "--format", "sarif", "--classpath-file", classpathFile().toString());
        JsonNode inv = OrderJson.MAPPER.readTree(r.out()).path("runs").path(0).path("invocations").path(0);
        assertThat(inv.path("executionSuccessful").asBoolean(true)).isFalse();
        assertThat(inv.path("toolExecutionNotifications").toString()).contains("Broken.java");
    }

    @Test
    void missingClasspathFailsTheGate() throws IOException {
        Path app = project(false);
        Run r = run("scan", app.toString(), "--fail-on", "high", "--format", "json");
        assertThat(r.exit()).as(r.err()).isEqualTo(4);
        assertThat(r.err()).contains("analysis incomplete").contains("no classpath");
    }

    @Test
    void baselineFromAnotherVersionIsFlagged() throws IOException {
        Path app = project(false);
        String cp = classpathFile().toString();
        assertThat(run("scan", app.toString(), "--update-baseline", "--classpath-file", cp).exit()).isZero();
        Path file = app.resolve(".logast/baseline.json");
        JsonNode recorded = OrderJson.MAPPER.readTree(file.toFile());
        assertThat(recorded.path("version").asText()).isEqualTo(dev.logast.orders.Baseline.currentVersion());
        assertThat(run("scan", app.toString(), "--baseline", "--fail-on", "high", "--classpath-file", cp).err())
                .doesNotContain("baseline recorded by");

        ((com.fasterxml.jackson.databind.node.ObjectNode) recorded).put("version", "0.0.1-older");
        OrderJson.MAPPER.writeValue(file.toFile(), recorded);
        Run r = run("scan", app.toString(), "--baseline", "--fail-on", "high", "--classpath-file", cp);
        assertThat(r.exit()).isZero();
        assertThat(r.err()).contains("baseline recorded by logast 0.0.1-older");
    }
}
