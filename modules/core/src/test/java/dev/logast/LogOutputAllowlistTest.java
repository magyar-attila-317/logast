package dev.logast;

import dev.logast.orders.Edit;
import dev.logast.orders.WorkOrder;
import dev.logast.policy.Policy;
import dev.logast.rules.MessageTemplate;
import dev.logast.rules.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #296: an encoder that lists the MDC keys it writes drops the others, so the keys the code puts into MDC are added to
 * the list (an excluded key is only reported). #310: the nouns of a chain of labelled values go with the values.
 */
class LogOutputAllowlistTest {
    private static final String LOGBACK = """
            <configuration>
                <springProfile name="json">
                    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
                        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
                            <includeMdcKeyName>userId</includeMdcKeyName>
                            <includeMdcKeyName>traceId</includeMdcKeyName>
                        </encoder>
                    </appender>
                </springProfile>
            </configuration>
            """;

    @Test
    void keysTheCodeWritesAreAddedToTheEncodersList(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.createDirectories(dir.resolve("src/main/resources"));
        Files.writeString(dir.resolve("src/main/resources/logback-spring.xml"), LOGBACK);
        Files.writeString(src.resolve("Orders.java"), """
                package x;

                import org.slf4j.MDC;

                public class Orders {
                    void handle(String orderId, String userId) {
                        MDC.put("orderId", orderId);
                        MDC.put("userId", userId);
                        try {
                            System.out.println(orderId);
                        } finally {
                            MDC.remove("orderId");
                            MDC.remove("userId");
                        }
                    }
                }
                """);
        WorkOrder o = scan(dir).stream().filter(w -> w.rule().equals(Rules.MDC_KEY_NOT_IN_LOG_OUTPUT)).findFirst().orElseThrow();
        Edit e = o.edits().getFirst();
        String after = LOGBACK.substring(0, e.start()) + e.text() + LOGBACK.substring(e.end());
        assertThat(after).contains("<includeMdcKeyName>traceId</includeMdcKeyName>\n"
                + "                <includeMdcKeyName>orderId</includeMdcKeyName>\n            </encoder>");
        assertThat(after.split("<includeMdcKeyName>userId", -1)).as("a listed key is not added again").hasSize(2);

        Files.writeString(dir.resolve("src/main/resources/logback-spring.xml"), LOGBACK.replace(
                "<includeMdcKeyName>traceId</includeMdcKeyName>", "<includeMdcKeyName>traceId</includeMdcKeyName>\n<includeMdcKeyName>orderId</includeMdcKeyName>"));
        assertThat(scan(dir)).as("every key listed").noneMatch(w -> w.rule().equals(Rules.MDC_KEY_NOT_IN_LOG_OUTPUT));
        Files.writeString(dir.resolve("src/main/resources/logback-spring.xml"), LOGBACK.replace("</includeMdcKeyName>\n            </encoder>",
                "</includeMdcKeyName>\n<excludeMdcKeyName>orderId</excludeMdcKeyName>\n            </encoder>"));
        assertThat(scan(dir)).filteredOn(w -> w.rule().equals(Rules.MDC_KEY_NOT_IN_LOG_OUTPUT)).singleElement()
                .satisfies(w -> assertThat(w.edits()).as("excluded on purpose: reported").isEmpty());
        Files.writeString(dir.resolve("src/main/resources/logback-spring.xml"), "<configuration>\n</configuration>\n");
        assertThat(scan(dir)).as("no list: every key is written").noneMatch(w -> w.rule().equals(Rules.MDC_KEY_NOT_IN_LOG_OUTPUT));
    }

    @Test
    void theNounsOfAChainOfLabelledValuesGoWithThem() {
        List<Set<String>> labels = List.of(Set.of("thing", "thingid"), Set.of("order", "orderid"), Set.of("tenant"), Set.of("message"));
        assertThat(MessageTemplate.dropLabels("RECOVERY_FAILED: thing {} order {} tenant {}: {}", labels))
                .isEqualTo("RECOVERY_FAILED: {} {} {}: {}");
        assertThat(MessageTemplate.dropLabels("Order {} shipped", List.of(Set.of("order"))))
                .as("a single labelled value is the subject").isEqualTo("Order {} shipped");
        assertThat(MessageTemplate.dropLabels("thing {} not found (order {}) - skipping", List.of(Set.of("thing"), Set.of("order"))))
                .as("a labelled value in parentheses goes with its noun").isEqualTo("thing {} not found ({}) - skipping");
    }

    @Test
    void aChainOfLabelledValuesLosesItsNouns(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Recovery.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                public class Recovery {
                    private static final Logger log = LoggerFactory.getLogger(Recovery.class);

                    void recover(String thingId, String orderId, String tenant) {
                        try {
                            run();
                        } catch (IllegalStateException e) {
                            log.error("RECOVERY_FAILED: thing {} order {} tenant {}: {}", thingId, orderId, tenant, e.getMessage(), e);
                        }
                    }

                    void run() {
                    }
                }
                """);
        String texts = String.join("\n", scan(dir).stream().filter(o -> "Recovery.recover".equals(o.method()))
                .flatMap(o -> o.slots().values().stream()).map(sl -> sl.original() + " | " + sl.generated()).toList());
        assertThat(texts).contains("RECOVERY_FAILED").as("the labels of the values that leave the prose")
                .doesNotContain("thing {}").doesNotContain("order {}");
    }

    /** #310: an id already in MDC, in parentheses after another value: the group goes with it. */
    @Test
    void aParenthesisedIdInMdcLeavesNoGroupBehind(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("logast.yaml"), """
                vocabulary:
                  order:
                    mdc: { key: orderId, sources: { params: [orderId] } }
                """);
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Seating.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.slf4j.MDC;

                public class Seating {
                    private static final Logger log = LoggerFactory.getLogger(Seating.class);

                    void seat(String orderId, String tableId) {
                        try (var c = MDC.putCloseable("orderId", orderId)) {
                            log.warn("table {} not found (order {}) - skipping", tableId, orderId);
                        }
                    }
                }
                """);
        String texts = String.join("\n", scan(dir).stream().filter(o -> "Seating.seat".equals(o.method()))
                .flatMap(o -> java.util.stream.Stream.concat(o.edits().stream().map(Edit::text),
                        o.slots().values().stream().map(sl -> sl.original() + "|" + sl.generated()))).toList());
        assertThat(texts).contains("not found").doesNotContain("(order)").doesNotContain("(order {})");
    }

    private static List<WorkOrder> scan(Path dir) throws java.io.IOException {
        return Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null))).orders();
    }
}
