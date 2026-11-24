package dev.logast;

import dev.logast.orders.Edit;
import dev.logast.orders.WorkOrder;
import dev.logast.policy.Policy;
import dev.logast.rules.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** #301: the cause goes right after the last argument, also when the closing parenthesis is on a line of its own. */
class CauseInsertTest {
    @Test
    void theCauseFollowsTheLastArgument(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        String keys = """
                package x;

                public class Keys {
                    Object parse(String raw) {
                        try {
                            return Integer.valueOf(raw);
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException(
                                    "Invalid key format: " + e.getMessage()
                            );
                        }
                    }
                }
                """;
        Files.writeString(src.resolve("Keys.java"), keys);
        WorkOrder o = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null))).orders().stream()
                .filter(w -> w.rule().equals(Rules.CAUSE_DROPPED)).findFirst().orElseThrow();
        Edit e = o.edits().getFirst();
        String after = keys.substring(0, e.start()) + e.text() + keys.substring(e.end());
        assertThat(after).contains("\"Invalid key format: \" + e.getMessage(), e\n            );");
    }
}
