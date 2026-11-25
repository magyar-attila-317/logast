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

/** #273: an outcome the service already counts through its own metrics helper gets no second counter. */
class ExistingCounterTest {
    @Test
    void theServicesOwnCounterIsUsed(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("logast.yaml"), "packs: [logging, metrics]\n");
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("EventMetrics.java"), """
                package x;

                import io.micrometer.core.instrument.MeterRegistry;
                import org.springframework.stereotype.Component;

                @Component
                public class EventMetrics {
                    private final MeterRegistry registry;

                    public EventMetrics(MeterRegistry registry) {
                        this.registry = registry;
                    }

                    public void publishFailed(String event) {
                        registry.counter("events.publish", "event", event, "outcome", "failed").increment();
                    }
                }
                """);
        Files.writeString(src.resolve("Publisher.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.stereotype.Component;

                @Component
                public class Publisher {
                    private static final Logger log = LoggerFactory.getLogger(Publisher.class);
                    private final EventMetrics metrics;

                    public Publisher(EventMetrics metrics) {
                        this.metrics = metrics;
                    }

                    public void created(String id) {
                        try {
                            send(id);
                        } catch (IllegalStateException e) {
                            log.error("Order created publish failed", e);
                            metrics.publishFailed("created");
                        }
                    }

                    public void updated(String id) {
                        try {
                            send(id);
                        } catch (IllegalStateException e) {
                            log.error("Order updated publish failed", e);
                        }
                    }

                    void send(String id) {
                    }
                }
                """);
        List<WorkOrder> orders = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null))).orders().stream()
                .filter(o -> o.rule().equals(Rules.METRIC_OUTCOME_UNMEASURED)).toList();
        assertThat(orders).as("counted by the service's own helper").noneMatch(o -> "Publisher.created".equals(o.method()));
        assertThat(orders).as("nothing counts it").anyMatch(o -> "Publisher.updated".equals(o.method()));
    }
}
