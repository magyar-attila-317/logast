package dev.logast;

import dev.logast.policy.Policy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** #281: a log call keeps its id and history across runs while the code around it moves and its text changes. */
class LogInventoryTest {
    private static final String SERVICE = """
            package x;

            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;

            public class Shipping {
                private static final Logger log = LoggerFactory.getLogger(Shipping.class);

                public void ship(String orderId) {
            ABOVE        log.info("TEXT", orderId);
                }
            }
            """;

    private static Analysis write(Path dir, String above, String text) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Shipping.java"), SERVICE.replace("ABOVE", above).replace("TEXT", text));
        return Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null));
    }

    @Test
    void aLineKeepsItsIdWhenALineIsAddedAboveAndItsTextChanges(@TempDir Path dir) throws Exception {
        LogInventory first = LogInventory.load(dir);
        first.observe(write(dir, "", "Shipping order {}"), LogInventory.PERSON);
        first.save(dir);
        String id = first.entries().getFirst().id();
        assertThat(first.entries().getFirst().origin()).isEqualTo("existing");
        assertThat(first.entries().getFirst().wording()).isEqualTo("original");

        // logast adds a line above and rewords the existing one
        LogInventory second = LogInventory.load(dir);
        second.observe(write(dir, "        log.debug(\"Order shipment started\");\n", "Shipping order {} now"), LogInventory.LOGAST);
        second.save(dir);
        List<LogInventory.Entry> entries = second.entries();
        assertThat(entries).hasSize(2);
        LogInventory.Entry kept = entries.get(1);
        assertThat(kept.id()).isEqualTo(id);
        assertThat(kept.line()).isEqualTo(11);
        assertThat(kept.wording()).isEqualTo("logast");
        assertThat(kept.history()).containsExactly("run 1: found", "run 2: logast changed the text");
        assertThat(entries.getFirst().origin()).isEqualTo("added");

        // a person then changes the text: the next run's first look records it
        LogInventory third = LogInventory.load(dir);
        third.observe(write(dir, "        log.debug(\"Order shipment started\");\n", "Order {} handed to the carrier"), LogInventory.PERSON);
        assertThat(third.entries().get(1).id()).isEqualTo(id);
        assertThat(third.entries().get(1).wording()).isEqualTo("person");
        assertThat(third.entries().getFirst().origin()).isEqualTo("added");
    }
}
