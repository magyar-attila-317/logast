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

/**
 * #304: a line in a catch that rethrows, but does not log the exception (the FAILED half of an OK/FAILED status pair),
 * is not "logged and rethrown": it is never deleted.
 */
class RethrowStatusLineTest {
    @Test
    void aStatusLineBeforeARethrowStays(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Cleanup.java"), """
                package x;

                import java.util.List;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                public class Cleanup {
                    private static final Logger log = LoggerFactory.getLogger(Cleanup.class);

                    void each(List<String> names) {
                        for (String name : names) {
                            long start = System.nanoTime();
                            try {
                                run(name);
                                log.info("Item summary: item={} outcome=OK elapsedMs={}", name, (System.nanoTime() - start) / 1_000_000);
                            } catch (RuntimeException e) {
                                log.error("Item summary: item={} outcome=FAILED elapsedMs={}", name, (System.nanoTime() - start) / 1_000_000);
                                throw e;
                            }
                        }
                    }

                    void logged(String name) {
                        try {
                            run(name);
                        } catch (RuntimeException e) {
                            log.error("Cleanup failed", e);
                            throw e;
                        }
                    }

                    void run(String name) {
                    }
                }
                """);
        List<WorkOrder> rethrow = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null))).orders().stream()
                .filter(o -> o.rule().equals(Rules.LOG_AND_RETHROW) || o.rule().equals(Rules.EXCEPTION_LOGGED_TWICE)).toList();
        assertThat(rethrow).as("the exception logged and rethrown").anyMatch(o -> o.method().equals("Cleanup.logged"));
        assertThat(rethrow).as("a status line without the exception").noneMatch(o -> o.method().equals("Cleanup.each"));
    }
}
