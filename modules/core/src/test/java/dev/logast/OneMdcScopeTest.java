package dev.logast;

import dev.logast.orders.Edit;
import dev.logast.orders.WorkOrder;
import dev.logast.policy.Policy;
import dev.logast.rules.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** #311: a key for a method whose body already is one MdcScope goes into that scope's call, not into a nested one. */
class OneMdcScopeTest {
    @Test
    void aSecondKeyJoinsTheExistingScope(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("logast.yaml"), """
                vocabulary:
                  order:
                    mdc: { key: orderId, sources: { params: [orderId] } }
                  table:
                    mdc: { key: tableId, sources: { params: [tableId] } }
                """);
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("MdcScope.java"), """
                package x;

                public final class MdcScope implements AutoCloseable {
                    public static MdcScope of(String key, Object value, Object... more) {
                        return new MdcScope();
                    }

                    @Override
                    public void close() {
                    }
                }
                """);
        String seating = """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                public class Seating {
                    private static final Logger log = LoggerFactory.getLogger(Seating.class);

                    void seat(String orderId, String tableId) {
                        try (var mdc = MdcScope.of("orderId", orderId)) {
                            log.info("Table checked [tableId={}]", tableId);
                            log.info("Guests seated [tableId={}]", tableId);
                            log.info("Seating completed [tableId={}]", tableId);
                        }
                    }
                }
                """;
        Files.writeString(src.resolve("Seating.java"), seating);
        WorkOrder o = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null))).orders().stream()
                .filter(w -> w.rule().equals(Rules.REPEATED_LOG_VALUE) && "Seating.seat".equals(w.method())).findFirst().orElseThrow();
        String after = seating;
        for (Edit e : o.edits().stream().filter(e -> e.file().endsWith("Seating.java"))
                .sorted((a, b) -> Integer.compare(b.start(), a.start())).toList()) {
            after = after.substring(0, e.start()) + e.text() + after.substring(e.end());
        }
        assertThat(after).as("one scope with both keys").contains("try (var mdc = MdcScope.of(\"orderId\", orderId, \"tableId\", tableId)) {")
                .doesNotContain("mdc2");
    }

    /** #311: ids read into locals one after the other get one scope after the last declaration, not one per id. */
    @Test
    void localsDeclaredTogetherGetOneScope(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("logast.yaml"), """
                vocabulary:
                  order:
                    mdc: { key: orderId }
                  table:
                    mdc: { key: tableId }
                """);
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Moved.java"), "package x;\n\npublic record Moved(String orderId, String tableId) {\n}\n");
        String listener = """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                public class MoveLog {
                    private static final Logger log = LoggerFactory.getLogger(MoveLog.class);

                    void on(Moved event) {
                        String orderId = event.orderId();
                        String tableId = event.tableId();
                        log.info("Move checked [orderId={}, tableId={}]", orderId, tableId);
                        log.info("Move booked [orderId={}, tableId={}]", orderId, tableId);
                        log.info("Move completed [orderId={}, tableId={}]", orderId, tableId);
                    }
                }
                """;
        Files.writeString(src.resolve("MoveLog.java"), listener);
        List<WorkOrder> orders = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null))).orders().stream()
                .filter(w -> w.rule().equals(Rules.REPEATED_LOG_VALUE) && "MoveLog.on".equals(w.method())).toList();
        assertThat(orders).hasSize(1);
        String edits = String.join("\n", orders.getFirst().edits().stream().map(Edit::text).toList());
        assertThat(edits).as("one scope after both declarations").contains("MdcScope.of(\"orderId\", orderId, \"tableId\", tableId)")
                .doesNotContain("mdc2");
    }
}
