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

/** #298: a job that runs its work through the team's own lock is locked; one that does not is reported. */
class JobLockTest {
    @Test
    void aJobThroughItsOwnLockIsLocked(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("logast.yaml"), "packs: [resilience]\n");
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("JobLock.java"), """
                package x;

                public class JobLock {
                    public void runExclusive(String task, Runnable work) {
                        work.run();
                    }
                }
                """);
        Files.writeString(src.resolve("Sweeps.java"), """
                package x;

                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;

                @Component
                public class Sweeps {
                    private final JobLock jobLock = new JobLock();

                    @Scheduled(cron = "0 0 * * * *")
                    public void locked() {
                        jobLock.runExclusive("sweep", this::sweep);
                    }

                    @Scheduled(cron = "0 30 * * * *")
                    public void unlocked() {
                        sweep();
                    }

                    void sweep() {
                    }
                }
                """);
        List<WorkOrder> orders = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null))).orders();
        WorkOrder o = orders.stream().filter(w -> w.rule().equals(Rules.SCHEDULED_WITHOUT_LOCK)).findFirst().orElseThrow();
        assertThat(o.title()).contains("Sweeps.unlocked").doesNotContain("Sweeps.locked");
    }
}
