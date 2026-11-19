package dev.logast;

import dev.logast.model.MethodInfo;
import dev.logast.policy.Policy;
import dev.logast.rules.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * #136: methods sharing a key (overloads whose parameter types do not resolve, as with an incomplete classpath) are
 * each analysed; the scan used to fail with a NullPointerException in the audit rules.
 */
class UnresolvedOverloadsTest {
    @Test
    void overloadsWhoseParameterTypesDoNotResolveAreAnalysedEach(@TempDir Path dir) throws Exception {
        Files.writeString(Files.createDirectories(dir.resolve("src/main/java/x")).resolve("Overloads.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import y.missing.First;
                import y.missing.Second;

                class Overloads {
                    private static final Logger log = LoggerFactory.getLogger(Overloads.class);

                    void handle(First first) {
                        log.info("First handled [first={}]", first);
                    }

                    void handle(Second second) {
                        log.info("Second handled [second={}]", second);
                    }
                }
                """);
        Analysis a = Analysis.run(dir, Fixtures.classpath(),
                Policy.resolve(dir, null).withPacks(List.of("logging", "tracing", "metrics", "resilience", "errors", "audit")));
        List<MethodInfo> methods = a.index.methodsOf("x.Overloads");
        assertThat(methods).as("both overloads indexed").hasSize(2);
        methods.forEach(m -> assertThat(m.facts()).as("facts of " + m.key()).isNotNull());
        assertThatCode(() -> Rules.scan(a)).doesNotThrowAnyException();
    }
}
