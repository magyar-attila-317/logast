package dev.logast.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** #56: what the CLI says about a policy that means nothing (error) or probably the wrong thing (warning). */
class PolicyCheckCliTest {
    @TempDir
    Path tmp;

    record Run(int exit, String err) {
    }

    private Path project(String policy) throws IOException {
        Path app = tmp.resolve("app");
        Path pkg = app.resolve("src/main/java/demo");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Ok.java"), "package demo;\n\nclass Ok {\n    int one() {\n        return 1;\n    }\n}\n");
        Files.writeString(app.resolve("logast.yaml"), policy);
        return app;
    }

    private static Run run(String... args) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int rc;
        try {
            System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(e, true, StandardCharsets.UTF_8));
            rc = new CommandLine(new Main()).execute(args);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        return new Run(rc, e.toString(StandardCharsets.UTF_8).replaceAll("\u001b\\[[0-9;]*m", ""));
    }

    @Test
    void aWarningIsPrintedOnceAndTheScanRuns() throws IOException {
        Path app = project("metrics:\n  catalogClass: com.example.metrics.BusinessMetrics\n");
        Run r = run("scan", app.toString(), "--format", "json");
        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.err()).containsOnlyOnce("policy: logast.yaml: metrics.catalogClass: is only used with metrics.catalog: single");
    }

    @Test
    void anErrorStopsTheScan() throws IOException {
        Path app = project("rules:\n  LOG_FORAMT: { enabled: false }\n");
        Run r = run("scan", app.toString(), "--format", "json");
        assertThat(r.exit()).isNotZero();
        assertThat(r.err()).contains("logast.yaml: rules.LOG_FORAMT: unknown rule id; did you mean LOG_FORMAT?");
    }

    @Test
    void unknownRuleAndPackOptionsAreRejected() throws IOException {
        Path app = project("packs: [logging]\n");
        Run rule = run("scan", app.toString(), "--format", "json", "--rule", "LOG_FORAMT");
        assertThat(rule.exit()).isEqualTo(2);
        assertThat(rule.err()).contains("--rule LOG_FORAMT: unknown rule id; did you mean LOG_FORMAT?");
        Run pack = run("scan", app.toString(), "--format", "json", "--pack", "loging");
        assertThat(pack.exit()).isNotZero();
        assertThat(pack.err()).contains("--pack loging: unknown pack; did you mean logging?");
    }
}
