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
 * #334 (a dry run on a real service): a job whose class says it wants no logging gets no lines; a job that runs more
 * often than hourly gets its started / completed lines at DEBUG (every 10 minutes is 288 INFO lines a day); the count
 * a delete returns is "deleted", not the repository method's name.
 */
class JobLinesRateTest {
    @Test
    void jobLinesFollowTheRateAndTheOwnersWish(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Store.java"), """
                package x;

                public class Store {
                    public int deleteStaleThings(long cutoff) {
                        return 0;
                    }
                }
                """);
        Files.writeString(src.resolve("Sweeper.java"), """
                package x;

                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;

                /**
                 * Removes stale things. No logging by owner rule.
                 */
                @Component
                public class Sweeper {
                    private final Store store = new Store();

                    @Scheduled(cron = "0 0 3 * * *")
                    public int sweep() {
                        return store.deleteStaleThings(1L);
                    }
                }
                """);
        Files.writeString(src.resolve("Cleanup.java"), """
                package x;

                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;

                @Component
                public class Cleanup {
                    private final Store store = new Store();

                    @Scheduled(fixedDelay = 600000)
                    public int expire() {
                        return store.deleteStaleThings(2L);
                    }

                    @Scheduled(cron = "0 0 * * * *")
                    public int hourly() {
                        return store.deleteStaleThings(3L);
                    }
                }
                """);
        List<WorkOrder> orders = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null))).orders();
        assertThat(edits(orders, "Sweeper.sweep")).as("its class asks for no logging").doesNotContain("log.");
        String expire = edits(orders, "Cleanup.expire");
        assertThat(expire).as("every 10 minutes: DEBUG").contains("log.debug(").doesNotContain("log.info(")
                .as("the count a delete returns").contains("deleted={}").doesNotContain("deleteStaleThings={}");
        assertThat(edits(orders, "Cleanup.hourly")).as("hourly: INFO").contains("log.info(");
    }

    private static String edits(List<WorkOrder> orders, String method) {
        StringBuilder sb = new StringBuilder();
        orders.stream().filter(o -> method.equals(o.method())).forEach(o -> o.edits().forEach(e -> sb.append(e.text()).append('\n')));
        return sb.toString();
    }
}
