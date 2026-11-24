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
 * #306: a started line only where its rate allows one: not in a job polling every minute (a placeholder with a default
 * and a timeUnit), not in an application event listener without a rate of its own; a daily job keeps one.
 */
class StartedLineRateTest {
    @Test
    void startedLinesFollowTheRate(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Poller.java"), """
                package x;

                import java.util.concurrent.TimeUnit;
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;

                @Component
                public class Poller {
                    @Scheduled(fixedDelayString = "${poll.interval-seconds:60}", timeUnit = TimeUnit.SECONDS)
                    public void poll() {
                        refresh();
                    }

                    @Scheduled(cron = "0 0 3 * * *")
                    public void nightly() {
                        refresh();
                    }

                    void refresh() {
                    }
                }
                """);
        Files.writeString(src.resolve("ThingMoved.java"), """
                package x;

                public record ThingMoved(String thingId) {
                }
                """);
        Files.writeString(src.resolve("MoveListener.java"), """
                package x;

                import org.springframework.stereotype.Component;
                import org.springframework.transaction.event.TransactionPhase;
                import org.springframework.transaction.event.TransactionalEventListener;

                @Component
                public class MoveListener {
                    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
                    public void onMoved(ThingMoved event) {
                        notifyBoard(event.thingId());
                    }

                    void notifyBoard(String thingId) {
                    }
                }
                """);
        List<WorkOrder> orders = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null))).orders();
        assertThat(startedLines(orders, "Poller.nightly")).as("a daily job keeps its started line").isTrue();
        assertThat(startedLines(orders, "Poller.poll")).as("a job every 60 s").isFalse();
        assertThat(startedLines(orders, "MoveListener.onMoved")).as("an event listener without a rate").isFalse();
    }

    private static boolean startedLines(List<WorkOrder> orders, String method) {
        return orders.stream().filter(o -> method.equals(o.method()))
                .anyMatch(o -> o.edits().stream().anyMatch(e -> e.text() != null && e.text().contains("started")));
    }
}
