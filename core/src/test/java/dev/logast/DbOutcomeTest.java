package dev.logast;

import dev.logast.orders.ScanResult;
import dev.logast.orders.WorkOrder;
import dev.logast.policy.Policy;
import dev.logast.rules.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** DB_OUTCOME_UNLOGGED: JdbcTemplate methods log their outcome; the level follows the paths that reach them. */
class DbOutcomeTest {
    @Test
    void writesInfoReadsByPathLoopsDebug(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("OrderItemRepository.java"), """
                package x;

                import java.util.List;
                import java.util.UUID;
                import org.springframework.jdbc.core.JdbcTemplate;
                import org.springframework.stereotype.Repository;

                @Repository
                public class OrderItemRepository {
                    private static final String CLOSE = "UPDATE order_items SET closed = true WHERE order_id = ?";
                    private static final String UPSERT = \"""
                            INSERT INTO preview_counters (shift_id, preview_count) VALUES (?, 1)
                            ON CONFLICT (shift_id) DO UPDATE SET preview_count = preview_counters.preview_count + 1
                            RETURNING preview_count
                            \""";
                    private final JdbcTemplate jdbcTemplate;

                    OrderItemRepository(JdbcTemplate jdbcTemplate) {
                        this.jdbcTemplate = jdbcTemplate;
                    }

                    public void close(UUID orderId) {
                        jdbcTemplate.update(CLOSE, orderId);
                    }

                    public int increment(UUID shiftId) {
                        return jdbcTemplate.queryForObject(UPSERT, Integer.class, shiftId);
                    }

                    public List<String> openForJob() {
                        return jdbcTemplate.queryForList("SELECT id FROM order_items WHERE closed = false", String.class);
                    }

                    public List<String> openForRequest() {
                        return jdbcTemplate.queryForList("SELECT id FROM order_items WHERE closed = false", String.class);
                    }

                    public void touch(String id) {
                        jdbcTemplate.update("UPDATE order_items SET touched = now() WHERE id = ?", id);
                    }
                }
                """);
        Files.writeString(src.resolve("Callers.java"), """
                package x;

                import java.util.List;
                import java.util.UUID;
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.web.bind.annotation.*;

                @RestController
                public class Callers {
                    private final OrderItemRepository repo;

                    Callers(OrderItemRepository repo) {
                        this.repo = repo;
                    }

                    @Scheduled(cron = "0 0 2 * * *")
                    public void nightly() {
                        for (String id : repo.openForJob()) {
                            repo.touch(id);
                        }
                    }

                    @GetMapping("/items")
                    public List<String> items() {
                        return repo.openForRequest();
                    }

                    @PostMapping("/orders/{orderId}/close")
                    public int close(@PathVariable UUID orderId) {
                        repo.close(orderId);
                        return repo.increment(orderId);
                    }
                }
                """);
        ScanResult r = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null)));
        Map<String, String> titles = r.orders().stream().filter(o -> o.rule().equals(Rules.DB_OUTCOME_UNLOGGED))
                .collect(Collectors.toMap(WorkOrder::method, WorkOrder::title));
        assertThat(titles.get("OrderItemRepository.close")).contains("changes data", "INFO");
        assertThat(titles.get("OrderItemRepository.increment")).contains("changes data", "INFO");   // upsert via queryForObject
        assertThat(titles.get("OrderItemRepository.openForJob")).contains("reads data", "INFO");    // nightly job only
        assertThat(titles.get("OrderItemRepository.openForRequest")).contains("DEBUG", "GET /items");
        assertThat(titles.get("OrderItemRepository.touch")).contains("DEBUG", "loop");
        String edits = r.orders().stream().filter(o -> "OrderItemRepository.increment".equals(o.method()))
                .flatMap(o -> o.edits().stream()).map(e -> e.text()).collect(Collectors.joining("\n"));
        assertThat(edits).contains("int previewCount = jdbcTemplate.queryForObject(UPSERT, Integer.class, shiftId);",
                "AfterCommit.log(() -> log.info(\"Preview counters saved [previewCount={}]\", previewCount)); // logast:wording",
                "return previewCount;");
    }
}
