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

/**
 * #331: an id logast adds to a log line must not throw where the code did not. An accessor of a parameter that may be
 * null (a system actor) is read null-safe, in a catch always (a throwing argument would replace the catch's handling);
 * after the method has used the parameter, the plain accessor is fine.
 */
class NullSafeAddedValueTest {
    @Test
    void anAddedAccessorCannotThrow(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("logast.yaml"), """
                vocabulary:
                  globalUser:
                    mdc: { key: globalUserId, sources: { types: { x.UserEntity: "getGlobalUserId()" } } }
                """);
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("UserEntity.java"), """
                package x;

                public class UserEntity {
                    public String getGlobalUserId() { return "u"; }
                    public String getName() { return "n"; }
                }
                """);
        Files.writeString(src.resolve("Sender.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                public class Sender {
                    private static final Logger log = LoggerFactory.getLogger(Sender.class);

                    public String send(String id, UserEntity actor) {
                        try {
                            call(id);
                            return "OK";
                        } catch (RuntimeException e) {
                            log.error("Send failed [submission={}]", id, e);
                            record(id, actor);
                            return "FAILED";
                        }
                    }

                    public void rename(String id, UserEntity actor) {
                        String name = actor.getName();
                        log.warn("Rename failed [submission={}]", id);
                    }

                    void call(String id) {
                    }

                    void record(String id, UserEntity actor) {
                    }
                }
                """);
        List<WorkOrder> orders = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null))).orders();
        String send = edits(orders, "Sender.send");
        assertThat(send).as("in the catch: null-safe\n" + send).contains("actor == null ? null : actor.getGlobalUserId()");
        String rename = edits(orders, "Sender.rename");
        assertThat(rename).as("the method used actor before: the plain accessor\n" + rename)
                .contains("actor.getGlobalUserId()").doesNotContain("actor == null");
    }

    private static String edits(List<WorkOrder> orders, String method) {
        return String.join("\n", orders.stream().filter(o -> method.equals(o.method()) && o.rule().equals(Rules.LOG_ID_MISSING))
                .flatMap(o -> o.edits().stream()).map(Edit::text).filter(t -> t != null).toList());
    }
}
