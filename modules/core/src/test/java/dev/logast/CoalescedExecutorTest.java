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

/** #299: a single-thread executor fed only through a compare-and-set guard queues at most one task: not unbounded. */
class CoalescedExecutorTest {
    @Test
    void aGuardedHandOffIsBounded(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("logast.yaml"), "packs: [resilience]\n");
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Refresher.java"), """
                package x;

                import java.util.concurrent.ExecutorService;
                import java.util.concurrent.Executors;
                import java.util.concurrent.atomic.AtomicBoolean;

                public class Refresher {
                    private final ExecutorService guarded = Executors.newSingleThreadExecutor();
                    private final ExecutorService open = Executors.newSingleThreadExecutor();
                    private final AtomicBoolean pending = new AtomicBoolean();

                    void refresh(Runnable body) {
                        if (pending.compareAndSet(false, true)) {
                            guarded.submit(() -> {
                                try {
                                    body.run();
                                } finally {
                                    pending.set(false);
                                }
                            });
                        }
                    }

                    void any(Runnable body) {
                        open.submit(body);
                    }
                }
                """);
        List<WorkOrder> orders = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null))).orders().stream()
                .filter(o -> o.rule().equals(Rules.UNBOUNDED_EXECUTOR)).toList();
        assertThat(orders).as("only the executor fed without a guard").hasSize(1)
                .allSatisfy(o -> assertThat(o.line()).as("the open one").isEqualTo(9));
    }
}
