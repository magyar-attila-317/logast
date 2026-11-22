package dev.logast;

import dev.logast.apply.FillsFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** #108: an earlier .logast layout moves into decisions/ and reports/ without losing what a person wrote. */
class LogastDirTest {
    @TempDir
    Path root;

    @Test
    void movesAndConvertsTheEarlierLayout() throws IOException {
        Path dir = Files.createDirectories(root.resolve(".logast"));
        Files.writeString(dir.resolve("fills.json"), """
                {
                  "_readme" : "Write the message text ...",
                  "_status" : "1 finding(s) below need a message text.",
                  "SWA-1a2b3c" : {
                    "MSG" : "Invoice export failed: retried tonight",
                    "args" : [ "batchSize" ],
                    "_MSG" : "What failed | was: Export failed",
                    "_where" : "src/main/java/a/Export.java:12 Export.run"
                  }
                }
                """);
        Files.writeString(dir.resolve("traffic.json"), """
                {"data": {"result": [{"metric": {"method": "GET", "uri": "/orders"}, "value": [0, "120"]}]}}
                """);
        Files.writeString(dir.resolve("retry.yaml"), "# kept as written\nusages:\n  A.b:\n    specific: true\n");
        Files.createDirectories(dir.resolve("steps"));
        Files.writeString(dir.resolve("steps/01-setup.diff"), "diff");
        Files.writeString(dir.resolve("orders.json"), "{}");
        Files.writeString(root.resolve(".gitignore"), "build/\n\n# logast: machine-specific classpath and scan output; "
                + "baseline and traffic are shared\n.logast/*\n!.logast/baseline.json\n!.logast/traffic.yaml\n!.logast/retry.yaml\n"
                + "!.logast/README.md\n");

        LogastDir.Migration m = LogastDir.migrate(root);

        assertThat(m.line()).isEqualTo("moved to the new .logast layout: fills.json → decisions/messages.yaml, "
                + "retry.yaml → decisions/retry.yaml, traffic.json → decisions/traffic.yaml, orders.json, steps → reports/");
        assertThat(m.warnings()).isEmpty();
        assertThat(FillsFile.read(root.resolve(LogastDir.MESSAGES))).as("the text, its args and its hints survive")
                .hasEntrySatisfying("SWA-1a2b3c", f -> {
                    assertThat(f.slots()).containsEntry("MSG", "Invoice export failed: retried tonight");
                    assertThat(f.args()).containsExactly("batchSize");
                    assertThat(f.original("MSG")).isEqualTo("Export failed");
                    assertThat(f.file()).isEqualTo("src/main/java/a/Export.java");
                });
        assertThat(Files.readString(root.resolve(LogastDir.MESSAGES))).doesNotContain("_readme").doesNotContain("_status");
        assertThat(Traffic.load(root, null).perDay("GET /orders")).isEqualTo(120.0);
        assertThat(Files.readString(root.resolve(LogastDir.RETRY))).startsWith("# kept as written");
        assertThat(root.resolve(LogastDir.STEPS).resolve("01-setup.diff")).exists();
        assertThat(root.resolve(LogastDir.ORDERS)).exists();
        assertThat(dir.resolve("fills.json")).doesNotExist();
        assertThat(dir.resolve("traffic.json")).doesNotExist();
        assertThat(dir.resolve("steps")).doesNotExist();
        assertThat(Files.readString(root.resolve(".gitignore"))).as("decisions/ stays in git, the old entries are gone")
                .contains("!.logast/decisions/\n").doesNotContain("!.logast/traffic.yaml").doesNotContain("!.logast/retry.yaml")
                .contains("!.logast/baseline.json\n").startsWith("build/\n");
        assertThat(LogastDir.migrate(root).isEmpty()).as("a second run has nothing to move").isTrue();
    }

    @Test
    void anOldFileWhoseNewPlaceIsTakenStaysWithAWarning() throws IOException {
        Path dir = Files.createDirectories(root.resolve(".logast"));
        Files.writeString(dir.resolve("metrics.json"), "{\"OUM-1\": {\"count\": \"counter\"}}");
        Files.createDirectories(root.resolve(LogastDir.DECISIONS));
        Files.writeString(root.resolve(LogastDir.METRICS), "OUM-2:\n  count: skip\n");
        Files.createDirectories(root.resolve(LogastDir.REPORTS));
        Files.writeString(root.resolve(LogastDir.COVERAGE), "new");
        Files.writeString(dir.resolve("coverage.txt"), "stale");

        LogastDir.Migration m = LogastDir.migrate(root);

        assertThat(m.line()).isNull();
        assertThat(m.warnings()).singleElement().asString().contains(".logast/metrics.json is left in place");
        assertThat(dir.resolve("metrics.json")).as("nothing a person wrote is lost").exists();
        assertThat(Files.readString(root.resolve(LogastDir.METRICS))).isEqualTo("OUM-2:\n  count: skip\n");
        assertThat(dir.resolve("coverage.txt")).as("a stale report goes").doesNotExist();
        assertThat(Files.readString(root.resolve(LogastDir.COVERAGE))).isEqualTo("new");
    }

    @Test
    void noLogastFolderNothingToDo() {
        assertThat(LogastDir.migrate(root).isEmpty()).isTrue();
        assertThat(root.resolve(".logast")).doesNotExist();
    }
}
