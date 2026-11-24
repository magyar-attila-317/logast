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

/** #307: generated job lines keep acronyms, and two jobs of one class do not log the same line. */
class JobWordingTest {
    @Test
    void jobLinesReadWell(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("NTAKReportingJob.java"), """
                package x;

                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;

                @Component
                public class NTAKReportingJob {
                    @Scheduled(cron = "0 0 2 * * *")
                    public void run() {
                        send();
                    }

                    void send() {
                    }
                }
                """);
        Files.writeString(src.resolve("AuditCleanupJob.java"), """
                package x;

                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;

                @Component
                public class AuditCleanupJob {
                    @Scheduled(cron = "0 0 3 * * *")
                    public void archiveOld() {
                        work();
                    }

                    @Scheduled(cron = "0 0 4 * * *")
                    public void purgeArchived() {
                        work();
                    }

                    void work() {
                    }
                }
                """);
        List<WorkOrder> orders = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null))).orders();
        assertThat(texts(orders, "NTAKReportingJob.run")).as("the acronym keeps its capitals").contains("NTAK reporting started");
        assertThat(texts(orders, "AuditCleanupJob.archiveOld")).as("two jobs of one class name their method")
                .contains("Audit cleanup (archive old) started");
        assertThat(texts(orders, "AuditCleanupJob.purgeArchived")).contains("Audit cleanup (purge archived) started");
    }

    private static String texts(List<WorkOrder> orders, String method) {
        StringBuilder sb = new StringBuilder();
        orders.stream().filter(o -> method.equals(o.method())).forEach(o -> o.edits().forEach(e -> sb.append(e.text()).append('\n')));
        return sb.toString();
    }
}
