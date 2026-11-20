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
