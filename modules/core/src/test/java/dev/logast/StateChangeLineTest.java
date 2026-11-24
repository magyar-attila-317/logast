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
 * A line logast adds after a delete never calls the argument expression again (it would re-run the query, even at
 * DEBUG, and after the delete find nothing), and an entity class's "Entity" suffix is not part of its name.
 */
class StateChangeLineTest {
    @Test
    void aDeleteOfAQueryResultLogsWithoutRunningItAgain(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("MenuCategoryEntity.java"), """
                package x;

                public class MenuCategoryEntity {
                    String id;
                    String menuId;
                }
                """);
        Files.writeString(src.resolve("MenuCategoryEntityRepository.java"), """
                package x;

                import java.util.List;

                public interface MenuCategoryEntityRepository extends org.springframework.data.repository.CrudRepository<MenuCategoryEntity, String> {
                    List<MenuCategoryEntity> findByMenuId(String menuId);
                }
                """);
        Files.writeString(src.resolve("MenuCleanup.java"), """
                package x;

                import org.springframework.stereotype.Service;

                @Service
                public class MenuCleanup {
                    private final MenuCategoryEntityRepository categories;

                    public MenuCleanup(MenuCategoryEntityRepository categories) {
                        this.categories = categories;
                    }

                    public void clear(String menuId) {
                        categories.deleteAll(categories.findByMenuId(menuId));
                    }

                    public void copy(String menuId) {
                        for (MenuCategoryEntity c : categories.findByMenuId(menuId)) {
                            categories.save(c);
                        }
                    }
                }
                """);
        Files.writeString(src.resolve("MenuController.java"), """
                package x;

                import org.springframework.web.bind.annotation.DeleteMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class MenuController {
                    private final MenuCleanup cleanup;

                    public MenuController(MenuCleanup cleanup) {
                        this.cleanup = cleanup;
                    }

                    @DeleteMapping("/menus/{menuId}/categories")
                    public void clear(@PathVariable String menuId) {
                        cleanup.clear(menuId);
                        cleanup.copy(menuId);
                    }
                }
                """);
        List<WorkOrder> orders = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null))).orders();
        String edits = String.join("\n", orders.stream().filter(o -> "MenuCleanup.clear".equals(o.method()))
                .flatMap(o -> java.util.stream.Stream.concat(java.util.stream.Stream.concat(
                        o.edits().stream().map(e -> e.text() == null ? "" : e.text()),
                        o.slots().values().stream().map(sl -> sl.generated() + " " + sl.keys())),
                        java.util.stream.Stream.of(String.valueOf(o.data())))).toList());
        assertThat(edits).as("a line was added").contains("Menu categories deleted")
                .as("the query is not run again").doesNotContain("findByMenuId(menuId).size()")
                .doesNotContain("entitys");
        // E1: a save per loop item gets no line per item (the loop's summary counts them)
        assertThat(orders).noneMatch(o -> "MenuCleanup.copy".equals(o.method()) && o.rule().equals(Rules.STATE_CHANGE_UNLOGGED));
    }
}
