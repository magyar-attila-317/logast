package dev.logast;

import dev.logast.orders.WorkOrder;
import dev.logast.policy.Policy;
import dev.logast.rules.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** #303: an Object[] passed as SLF4J's varargs array holds the values of the placeholders; it is not one value to size. */
class VarargsArrayTest {
    @Test
    void theVarargsArrayIsLeftAlone(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Sweep.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                public class Sweep {
                    private static final Logger log = LoggerFactory.getLogger(Sweep.class);

                    void summary(int processed, int failed, long elapsedMs) {
                        String line = "Sweep summary: processed={} failed={} elapsedMs={}";
                        Object[] args = {processed, failed, elapsedMs};
                        if (failed > 0) {
                            log.error(line, args);
                        } else {
                            log.info(line, args);
                        }
                    }

                    void received(String sender, byte[] payload) {
                        log.info("Payload received: sender={} payload={}", sender, payload);
                    }
                }
                """);
        List<WorkOrder> sized = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null))).orders().stream()
                .filter(o -> o.rule().equals(Rules.LOG_COLLECTION_ARGUMENT)).toList();
        assertThat(sized).as("the byte[] value is still sized").anyMatch(o -> o.method().equals("Sweep.received"));
        assertThat(sized).as("the varargs array is not a value").noneMatch(o -> o.method().equals("Sweep.summary"));
    }
}
