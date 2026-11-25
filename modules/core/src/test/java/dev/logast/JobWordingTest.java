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

    /** #307: a collection endpoint names what it creates; a sub-resource's removal is not its upload; "..." goes. */
    @Test
    void endpointAndPhrasingWording(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".logast/decisions"));
        Files.writeString(dir.resolve(".logast/decisions/traffic.yaml"), """
                entrypoints:
                  "POST /menu/categories": 10
                  "POST /items/{id}/image": 10
                  "DELETE /items/{id}/image": 10
                  "POST /menu/tags": 10
                  "POST /orders/assign": 10
                  "POST /menu/validate-combination": 10
                """);
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("MenuController.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.web.bind.annotation.DeleteMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class MenuController {
                    private static final Logger log = LoggerFactory.getLogger(MenuController.class);

                    @PostMapping("/menu/categories")
                    public void createCategory() {
                        save();
                    }

                    @PostMapping("/items/{id}/image")
                    public void uploadImage(@PathVariable String id) {
                        save();
                    }

                    @DeleteMapping("/items/{id}/image")
                    public void removeImage(@PathVariable String id) {
                        save();
                    }

                    @PostMapping("/menu/tags")
                    public void createTag(String name,
                            String colour) {
                        save();
                    }

                    @PostMapping("/orders/assign")
                    public void assign() {
                        save();
                    }

                    @PostMapping("/menu/validate-combination")
                    public void validateCombination() {
                        save();
                    }

                    void reset() {
                        log.info("Starting E2E database reset...");
                    }

                    void save() {
                    }
                }
                """);
        List<WorkOrder> orders = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null))
                .withTraffic(Traffic.load(dir.toAbsolutePath().normalize(), null))).orders();
        assertThat(texts(orders, "MenuController.createCategory") + slots(orders, "MenuController.createCategory"))
                .contains("Category creation started");
        assertThat(slots(orders, "MenuController.uploadImage")).contains("Item image started");
        // #329: an action at the end of the path is what starts, not a creation
        assertThat(slots(orders, "MenuController.assign")).contains("Order assign started").doesNotContain("creation");
        assertThat(slots(orders, "MenuController.validateCombination")).contains("Validate combination started").doesNotContain("creation");
        assertThat(slots(orders, "MenuController.removeImage")).contains("Item image deletion started");
        assertThat(orders.stream().filter(o -> "MenuController.createTag".equals(o.method())).flatMap(o -> o.edits().stream())
                .map(e -> e.text()).filter(t -> t != null && t.contains("{{MSG}}")).findFirst().orElseThrow())
                .as("#241: the body's indent, not the wrapped parameter line's").startsWith("        log.debug(");   // #323 H4: an endpoint's started line is DEBUG
        assertThat(texts(orders, "MenuController.reset") + slots(orders, "MenuController.reset"))
                .contains("E2E database reset started").doesNotContain("..");
    }

    private static String slots(List<WorkOrder> orders, String method) {
        StringBuilder sb = new StringBuilder();
        orders.stream().filter(o -> method.equals(o.method())).forEach(o -> o.slots().values()
                .forEach(sl -> sb.append(sl.generated()).append('|').append(sl.original()).append('\n')));
        return sb.toString();
    }

    private static String texts(List<WorkOrder> orders, String method) {
        StringBuilder sb = new StringBuilder();
        orders.stream().filter(o -> method.equals(o.method())).forEach(o -> o.edits().forEach(e -> sb.append(e.text()).append('\n')));
        return sb.toString();
    }
}
