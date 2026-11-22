package dev.logast.rules;

import dev.logast.Analysis;
import dev.logast.Fixtures;
import dev.logast.model.ClassInfo;
import dev.logast.policy.Policy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #83: the name a log line uses when a local variable or parameter called like the logger would hide it: the static
 * field through its class, an instance field through {@code this} (or {@code Outer.this} from an inner class). The
 * applied case (a local {@code log} next to an outcome log) is HostileNamesTest#aLocalNamedLogDoesNotHideTheGeneratedLogger.
 */
class ShadowedLoggerTest {
    @Test
    void theLoggerIsNamedSoThatALocalCannotHideIt(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Shelf.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                public class Shelf {
                    private static final Logger log = LoggerFactory.getLogger(Shelf.class);

                    static class Box {
                    }
                }
                """);
        Files.writeString(src.resolve("Crate.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                public class Crate {
                    private final Logger logger = LoggerFactory.getLogger(getClass());

                    class Lid {
                    }
                }
                """);
        Files.writeString(src.resolve("Pallet.java"), """
                package x;

                @lombok.extern.slf4j.Slf4j
                public class Pallet extends Crate {
                }
                """);
        Files.writeString(src.resolve("Bare.java"), """
                package x;

                public class Bare {
                }
                """);
        Analysis a = Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null));

        assertThat(qualified(a, "Shelf")).isEqualTo("Shelf.log");
        assertThat(qualified(a, "Box")).as("a static nested class sees the outer static field").isEqualTo("Shelf.log");
        assertThat(qualified(a, "Crate")).isEqualTo("this.logger");
        assertThat(qualified(a, "Lid")).as("an inner class sees the outer instance field").isEqualTo("Crate.this.logger");
        assertThat(qualified(a, "Pallet")).as("Lombok's logger is static").isEqualTo("Pallet.log");
        assertThat(JavaEdits.logger(clazz(a, "Bare"), null, "log", false).qualified()).as("a field logast adds")
                .isEqualTo("Bare.log");
    }

    private static String qualified(Analysis a, String simpleName) {
        ClassInfo c = clazz(a, simpleName);
        return JavaEdits.logger(c, a.index.loggers().nearest(c), "log", false).qualified();
    }

    private static ClassInfo clazz(Analysis a, String simpleName) {
        return a.index.classes().stream().filter(c -> c.simpleName().equals(simpleName)).findFirst().orElseThrow();
    }
}
