package dev.logast.apply;

import dev.logast.orders.Slot;
import dev.logast.orders.WorkOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** #108: messages.yaml is YAML; what a person writes stays, and nothing is written when nothing changed. */
class FillsFileTest {
    @TempDir
    Path root;

    private static WorkOrder order(String id) {
        return order(id, "high");
    }

    private static WorkOrder order(String id, String severity) {
        return new WorkOrder(id, "SWALLOWED_EXCEPTION", List.of("SWALLOWED_EXCEPTION"), severity, "t", "src/A.java", 12, List.of(),
                "A.run", List.of(), null, List.of(), Map.of("MSG", new Slot("What failed", List.of(), List.of(), "Export failed")),
                null, List.of(), Map.of(), null, null, List.of());
    }

    @Test
    void writtenTextsStayAndAnUnchangedFileIsNotRewritten() throws IOException {
        Path file = root.resolve(FillsFile.PATH);
        assertThat(FillsFile.update(root, List.of(order("SWA-1"))).written()).isTrue();
        String created = Files.readString(file);
        assertThat(created).startsWith("# Optional. Write a message text").contains("# 1 finding(s) below need a message text.\n")
                .contains("SWA-1:\n  MSG: \"\"\n  _MSG: \"What failed | was: Export failed\"\n  _where: src/A.java:12 A.run\n");
        assertThat(FillsFile.update(root, List.of(order("SWA-1"))).written()).as("nothing changed").isFalse();

        // a person writes texts YAML would read otherwise unquoted: a colon, a number, a boolean word
        Files.writeString(file, created.replace("MSG: \"\"", "MSG: 'Export failed: retried tonight'\n  args: [batchSize]"));
        FillsFile.Update u = FillsFile.update(root, List.of(order("SWA-1")));
        assertThat(u.written()).as("what the person wrote is the content: nothing to rewrite").isFalse();
        assertThat(u.filled()).isEqualTo(1);
        assertThat(FillsFile.read(file).get("SWA-1").slots()).containsEntry("MSG", "Export failed: retried tonight");

        FillsFile.update(root, List.of(order("SWA-1"), order("SWA-2")));
        assertThat(FillsFile.read(file).get("SWA-1").args()).as("kept when the file is rewritten").containsExactly("batchSize");
        for (String text : List.of("123", "true", "no", "Charge failed: card \"declined\"", "{subject} failed")) {
            FillsFile.save(root, Map.of("SWA-2", Fill.of(Map.of("MSG", text), List.of())));
            assertThat(FillsFile.read(file).get("SWA-2").slots()).as("read back as written").containsEntry("MSG", text);
        }
    }

    /** #122: every decision file says what an entry left blank does, so nothing in it reads as required. */
    @Test
    void theHeadersSpellOutWhatABlankEntryDoes() throws IOException {
        FillsFile.update(root, List.of(order("SWA-1")));
        assertThat(DecisionYaml.header(root.resolve(FillsFile.PATH))).startsWith("# Optional.")
                .contains("Left blank: nothing changes, the current or generated text stays.").doesNotContain("--auto");
        WorkOrder outcome = new WorkOrder("OUM-1", MetricsFile.RULE, List.of(MetricsFile.RULE), "low", "t", "src/A.java", 12, List.of(),
                "A.run", List.of(), null, List.of(), Map.of(), null, List.of(),
                Map.of("name", "a.run", "outcome", "done", "event", "done", "message", "INFO \"Done\""), null, null, List.of());
        MetricsFile.update(root, List.of(outcome));
        assertThat(DecisionYaml.header(root.resolve(MetricsFile.PATH)).replace("\n# ", " ")).startsWith("# Optional.")
                .contains("Left blank: no metric is added.").doesNotContain("--auto");
        assertThat(dev.logast.Traffic.template(List.of(), dev.logast.Traffic.none()))
                .contains("# Optional. Left blank: no volume estimate");
    }

    @Test
    void anExplicitFillsFileIsYamlOrJsonByItsName() throws IOException {
        Path yaml = Files.writeString(root.resolve("texts.yaml"), "SWA-1:\n  MSG: Export failed\n  args: [batchSize]\n");
        Path json = Files.writeString(root.resolve("texts.json"), "{\"_readme\": \"x\", \"SWA-1\": {\"MSG\": \"Export failed\", \"args\": [\"batchSize\"]}}");
        for (Path p : List.of(yaml, json)) {
            Map<String, Fill> fills = FillsFile.forApply(root, p);
            assertThat(fills).containsOnlyKeys("SWA-1");
            assertThat(fills.get("SWA-1").slots()).containsEntry("MSG", "Export failed");
            assertThat(fills.get("SWA-1").args()).containsExactly("batchSize");
        }
    }

    /**
     * #292: a queue nobody can finish is no queue: at most logging.wordingQueue entries ask for a text, the most severe
     * first, a text already written always stays, and the header says how many wait.
     */
    @Test
    void theWordingQueueIsCappedMostSevereFirst() throws IOException {
        Path file = root.resolve(FillsFile.PATH);
        List<WorkOrder> orders = List.of(order("SWA-1", "low"), order("SWA-2", "low"), order("SWA-3", "high"), order("SWA-4", "medium"));
        FillsFile.update(root, orders, rule -> true, true, 2);
        assertThat(DecisionYaml.entries(file).keySet()).containsExactlyInAnyOrder("SWA-3", "SWA-4");
        assertThat(Files.readString(file)).contains("2 more keep their generated wording");

        Files.writeString(file, Files.readString(file).replace("SWA-4:\n  MSG: \"\"", "SWA-4:\n  MSG: \"Export failed, kept for retry\""));
        FillsFile.update(root, List.of(order("SWA-1", "high"), order("SWA-2", "high"), order("SWA-3", "high"), order("SWA-4", "low")),
                rule -> true, true, 2);
        assertThat(DecisionYaml.entries(file).keySet()).as("the written text stays; one more slot for the most severe")
                .hasSize(2).contains("SWA-4");
    }
}
