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

    /**
     * #323 H4: a write endpoint known to be quiet gets its started line at DEBUG: the access log and
     * http.server.requests already record each request at INFO. A second scan does not add another.
     */
    @Test
    void anEndpointsStartedLineIsDebug(@TempDir Path dir) throws Exception {
        Path decisions = Files.createDirectories(dir.resolve(".logast/decisions"));
        Files.writeString(decisions.resolve("traffic.yaml"), "entrypoints:\n  \"POST /things\": 10\n");
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("ThingController.java"), """
                package x;

                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class ThingController {
                    @PostMapping("/things")
                    public void create(@RequestBody String body) {
                        store(body);
                    }

                    void store(String body) {
                    }
                }
                """);
        List<WorkOrder> orders = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null))
                .withTraffic(Traffic.load(dir, null))).orders();
        List<String> entry = orders.stream().filter(o -> o.rule().equals(Rules.ENTRYPOINT_SILENT) && "ThingController.create".equals(o.method()))
                .flatMap(o -> o.edits().stream()).map(e -> e.text()).filter(t -> t != null && t.contains("{{MSG}}")).toList();
        assertThat(entry).as("a started line, at DEBUG").singleElement().satisfies(t -> assertThat(t).contains(".debug(\"{{MSG}}\")"));
        Files.writeString(src.resolve("ThingController.java"), Files.readString(src.resolve("ThingController.java"))
                .replace("        store(body);", "        log.debug(\"Thing creation started\");\n        store(body);")
                .replace("public class ThingController {", "public class ThingController {\n    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ThingController.class);"));
        List<WorkOrder> again = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null))
                .withTraffic(Traffic.load(dir, null))).orders();
        assertThat(again).as("the DEBUG line ends its silence").noneMatch(o -> o.rule().equals(Rules.ENTRYPOINT_SILENT));
    }
}
