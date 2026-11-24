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
 * G1/G2: suffix keys say what the value is. Two values the vocabulary maps to one key keep their own names (and stay);
 * a getter chain is named by its owner; a transformation by its source; an exception's class name is errorType.
 */
class SuffixKeyNamingTest {
    @Test
    void keysNameTheirValues(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("logast.yaml"), """
                vocabulary:
                  device:
                    mdc: { key: deviceId, sources: { params: [deviceId, tokenDeviceId, bodyDeviceId] } }
                """);
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("User.java"), "package x;\n\npublic record User(String id) {\n    public String getId() { return id; }\n}\n");
        Files.writeString(src.resolve("Token.java"), """
                package x;

                public record Token(String id, User user) {
                    public String getId() { return id; }
                    public User getUser() { return user; }
                }
                """);
        Files.writeString(src.resolve("Login.java"), """
                package x;

                import java.util.Locale;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                public class Login {
                    private static final Logger log = LoggerFactory.getLogger(Login.class);

                    void check(String tokenDeviceId, String bodyDeviceId, Token token, String kind) {
                        log.warn("Device mismatch tokenDeviceId={} bodyDeviceId={}", tokenDeviceId, bodyDeviceId);
                        log.info("token {} used for user id {}", token.getId(), token.getUser().getId());
                        log.info("Kind checked: {}", kind.toLowerCase(Locale.ROOT));
                        try {
                            run();
                        } catch (IllegalStateException e) {
                            log.warn("Check failed: {}", e.getClass().getSimpleName());
                        }
                    }

                    void run() {
                    }
                }
                """);
        List<WorkOrder> orders = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null))).orders();
        // a line in prose becomes NEEDS TEXT: its keys are the slot's; a mechanical rewrite writes them in the edit
        String edits = String.join("\n", orders.stream().filter(o -> "Login.check".equals(o.method()))
                .flatMap(o -> java.util.stream.Stream.concat(o.edits().stream().map(Edit::text).filter(t -> t != null),
                        o.slots().values().stream().flatMap(sl -> sl.keys().stream().map(k -> k + "={}")))).toList());
        assertThat(edits).as("both device ids, by their own names").contains("tokenDeviceId={}", "bodyDeviceId={}")
                .as("the user's id").contains("userId={}").doesNotContain("[id={}")
                .as("the source of a transformation").contains("kind={}").doesNotContain("toLowerCase={}")
                .as("an exception's class").contains("errorType={}").doesNotContain("simpleName={}");
    }
}
