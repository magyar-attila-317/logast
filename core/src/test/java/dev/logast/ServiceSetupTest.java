package dev.logast;

import dev.logast.policy.Policy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceSetupTest {
    @Test
    void termFromKey() {
        assertThat(ServiceSetup.term("orderId")).isEqualTo("order");
        assertThat(ServiceSetup.term("order_uuid")).isEqualTo("order");
        assertThat(ServiceSetup.term("order.id")).isEqualTo("order");
        assertThat(ServiceSetup.term("activeShiftId")).isEqualTo("active.shift");
        assertThat(ServiceSetup.term("tenant")).isEqualTo("tenant");
    }

    @Test
    void suggestsVocabularyFromFixtureMdcKeys() {
        Map<String, SortedSet<String>> keys = ServiceSetup.mdcKeysByTerm(Fixtures.root());
        assertThat(keys).containsKey("tenant");
        assertThat(keys.get("tenant")).contains("tenantId");
        assertThat(keys.get("order")).contains("order.id");
    }

    @Test
    void templateIsAValidPolicyAndSuggestionsUncommentCleanly(@TempDir Path dir) throws Exception {
        Map<String, SortedSet<String>> keys = Map.of("tenant", new TreeSet<>(List.of("tenantId")),
                "order", new TreeSet<>(List.of("order.id", "orderId")));
        String yaml = ServiceSetup.policyTemplate(List.of(), List.of("logging"), true, keys);
        Files.writeString(dir.resolve("logast.yaml"), yaml);
        Policy p = Policy.resolve(dir, null);
        assertThat(p.packs()).containsExactly("logging");
        assertThat(p.logging().rewriteExistingMessages()).isFalse();

        int from = yaml.indexOf("# vocabulary:"), to = yaml.indexOf("\n\n", from);
        String uncommented = yaml.substring(0, from) + yaml.substring(from, to).replaceAll("(?m)^# ", "") + yaml.substring(to);
        Files.writeString(dir.resolve("logast.yaml"), uncommented);
        Policy q = Policy.resolve(dir, null);
        assertThat(q.terms()).contains("tenant", "order");
    }

    @Test
    void gitignoreKeepsSharedFilesAndIsIdempotent(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(".gitignore"), "build/\n.logast/\n");
        assertThat(ServiceSetup.updateGitignore(dir)).containsExactlyElementsOf(ServiceSetup.GITIGNORE);
        assertThat(ServiceSetup.updateGitignore(dir)).isEmpty();
        assertThat(Files.readString(dir.resolve(".gitignore"))).contains("build/").doesNotContain(".logast/\n");
    }
}
