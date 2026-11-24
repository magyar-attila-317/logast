package dev.logast;

import dev.logast.orders.Slot;
import dev.logast.orders.WorkOrder;
import dev.logast.policy.Policy;
import dev.logast.rules.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** #319: a catch that drops the exception asks for "{subject} failed"; one that handles the outcome does not. */
class SwallowedPhrasingTest {
    @Test
    void aHandledOutcomeIsNotForcedIntoFailed(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Media.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                public class Media {
                    private static final Logger log = LoggerFactory.getLogger(Media.class);

                    boolean json(String contentType) {
                        try {
                            return parse(contentType);
                        } catch (IllegalArgumentException e) {
                            return false;
                        }
                    }

                    void copy(String path) {
                        try {
                            parse(path);
                        } catch (IllegalArgumentException e) {
                        }
                    }

                    boolean parse(String s) {
                        return s.isEmpty();
                    }
                }
                """);
        List<WorkOrder> orders = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null))).orders();
        Slot handled = slot(orders, "Media.json");
        Slot dropped = slot(orders, "Media.copy");
        assertThat(handled.event()).as("a fallback value: any wording").isNull();
        assertThat(dropped.event()).as("dropped: a failure").isEqualTo("failed");
    }

    private static Slot slot(List<WorkOrder> orders, String method) {
        return orders.stream().filter(o -> o.rule().equals(Rules.SWALLOWED_EXCEPTION) && method.equals(o.method()))
                .findFirst().orElseThrow().slots().get("MSG");
    }
}
