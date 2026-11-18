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
        return new WorkOrder(id, "SWALLOWED_EXCEPTION", List.of("SWALLOWED_EXCEPTION"), "high", "t", "src/A.java", 12, List.of(),
                "A.run", List.of(), null, List.of(), Map.of("MSG", new Slot("What failed", List.of(), List.of(), "Export failed")),
                null, List.of(), Map.of(), null, null, List.of());
    }

    @Test
    void writtenTextsStayAndAnUnchangedFileIsNotRewritten() throws IOException {
        Path file = root.resolve(FillsFile.PATH);
        assertThat(FillsFile.update(root, List.of(order("SWA-1"))).written()).isTrue();
        String created = Files.readString(file);
        assertThat(created).startsWith("# Write the message text").contains("# 1 finding(s) below need a message text.\n")
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
}
