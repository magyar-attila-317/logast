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

/** #152: `logast facts validate` reports each broken line as file:line and exits 1. */
class FactsCliTest {
    @TempDir
    Path tmp;

    private record Run(int exit, String out) {
    }

    private static Run run(String... args) {
        PrintStream out = System.out;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int rc;
        try {
            System.setOut(new PrintStream(o, true, StandardCharsets.UTF_8));
            rc = new CommandLine(new Main()).execute(args);
        } finally {
            System.setOut(out);
        }
        return new Run(rc, o.toString(StandardCharsets.UTF_8));
    }

    /** #156: the server entry for any MCP client's configuration. */
    @Test
    void mcpConfigPrintsTheServerEntry() throws IOException {
        Run r = run("mcp", "config", "--kb", tmp.toString());
        assertThat(r.exit()).isZero();
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(r.out());
        assertThat(json.at("/mcpServers/logast-kb/command").asText()).isEqualTo("logast");
        assertThat(json.at("/mcpServers/logast-kb/args").toString())
                .isEqualTo("[\"mcp\",\"--kb\",\"" + tmp.toAbsolutePath().normalize() + "\"]");
    }

    /** #210: clients start the server without a shell, so the entry asks the server itself to log its stderr. */
    @Test
    void mcpConfigLogsWithoutAShell() throws IOException {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        String tmpdir = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().toString();
        assertThat(json.readTree(run("mcp", "config", "--kb", tmp.toString(), "--code-mode", "--log").out())
                .at("/mcpServers/logast-kb/args").toString())
                .endsWith("\"--code-mode\",\"--log\",\"" + Path.of(tmpdir, "logast-mcp-code.log") + "\"]");
        Path f = tmp.resolve("mine.log");
        assertThat(json.readTree(run("mcp", "config", "--log", f.toString(), "--kb", tmp.toString()).out())
                .at("/mcpServers/logast-kb/args").toString())
                .isEqualTo("[\"mcp\",\"--kb\",\"" + tmp.toAbsolutePath().normalize() + "\",\"--log\",\"" + f.toAbsolutePath() + "\"]");
    }

    /** #210: the server's stderr and its messages in and out go to the --log file too, and a --kb path that does not exist is named there. */
    @Test
    void mcpLogsToItsFileAndNamesAMissingKb() throws IOException {
        Path missing = tmp.resolve("kb 2>>err.log");
        Path log = tmp.resolve("logs/mcp.log");
        var in = System.in;
        var err = System.err;
        try {
            System.setIn(new java.io.ByteArrayInputStream(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}\n".getBytes(StandardCharsets.UTF_8)));
            System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            assertThat(run("mcp", "--kb", missing.toString(), "--log", log.toString()).exit()).isZero();
        } finally {
            System.setIn(in);
            System.setErr(err);
        }
        String logged = Files.readString(log);
        assertThat(logged).contains("facts from --kb").contains(missing + " does not exist: no facts from it")
                .contains("> {\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}\n");
        assertThat(logged.lines().filter(l -> l.startsWith("< {")).toList()).singleElement().asString().contains("\"id\":1");
    }

    @Test
    void validFilesPassAndBrokenLinesAreNamed() throws IOException {
        String header = "{\"header\":{\"schema\":\"kb-facts/0\",\"extractor\":\"java\",\"repo\":\"shipping\"}}\n";
        Path good = Files.writeString(tmp.resolve("good.jsonl"), header
                + "{\"node\":{\"id\":\"service:shipping\",\"kind\":\"service\",\"evidence\":{\"file\":\"build.gradle.kts\"}}}\n");
        Path bad = Files.writeString(tmp.resolve("bad.jsonl"), header
                + "{\"node\":{\"id\":\"shipping\",\"kind\":\"service\",\"evidence\":{\"file\":\"build.gradle.kts\"}}}\n");

        Run ok = run("facts", "validate", good.toString());
        assertThat(ok.exit()).isZero();
        assertThat(ok.out()).contains("good.jsonl: 1 nodes, 0 edges, valid");

        Run failed = run("facts", "validate", good.toString(), bad.toString());
        assertThat(failed.exit()).isEqualTo(1);
        assertThat(failed.out()).contains(bad + ":2: id 'shipping' is not <kind>:<name>");
    }
}
