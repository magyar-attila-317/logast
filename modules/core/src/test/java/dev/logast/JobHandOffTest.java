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
 * #312: a job that only hands its work to an executor gets no "completed" line (it would say the work finished when it
 * was queued); a started line goes after the leading guards; a sweep next to a poller is named after its method.
 */
class JobHandOffTest {
    @Test
    void jobLinesSayWhatHappened(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("StatusJobs.java"), """
                package x;

                import java.util.concurrent.ExecutorService;
                import java.util.concurrent.Executors;
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;

                @Component
                public class StatusJobs {
                    private final ExecutorService executor = Executors.newSingleThreadExecutor();
                    private boolean active;

                    @Scheduled(fixedDelay = 60000)
                    public void poll() {
                        refresh();
                    }

                    @Scheduled(fixedDelay = 300000)
                    public void retrySweep() {
                        executor.submit(this::retry);
                    }

                    @Scheduled(cron = "0 */15 * * * *")
                    public void cleanup() {
                        if (!active) {
                            return;
                        }
                        refresh();
                    }

                    void refresh() {
                    }

                    void retry() {
                    }
                }
                """);
        List<WorkOrder> orders = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null))).orders();
        assertThat(edits(orders, "StatusJobs.retrySweep")).as("handed off: no line saying it completed").doesNotContain("completed");
        String cleanup = edits(orders, "StatusJobs.cleanup");
        assertThat(cleanup).as("named after its method, not the class's other job").contains("Status jobs (cleanup) started");
        WorkOrder o = orders.stream().filter(w -> "StatusJobs.cleanup".equals(w.method()) && w.rule().equals(Rules.JOB_OUTCOME_MISSING))
                .findFirst().orElseThrow();
        String text = Files.readString(src.resolve("StatusJobs.java"));
        int guardEnd = text.indexOf("        refresh();\n    }\n\n    void refresh");
        assertThat(o.edits()).filteredOn(e -> e.text() != null && e.text().contains("started")).singleElement()
                .satisfies(e -> assertThat(e.start()).as("after the guard").isEqualTo(guardEnd));
    }

    private static String edits(List<WorkOrder> orders, String method) {
        StringBuilder sb = new StringBuilder();
        orders.stream().filter(o -> method.equals(o.method())).forEach(o -> o.edits().forEach(e -> sb.append(e.text()).append('\n')));
        return sb.toString();
    }
}
