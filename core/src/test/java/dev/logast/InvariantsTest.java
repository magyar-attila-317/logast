package dev.logast;

import dev.logast.policy.Policy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The checks apply runs on every changed file: each one catches its kind of broken result. */
class InvariantsTest {
    @Test
    void catchesRepeatedKeysNestedScopesAndDoubledLifecycles(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(dir.resolve("logast.yaml"), """
                logging: { keyStyle: camel }
                vocabulary:
                  pledge: { mdc: { key: pledgeId, sources: { params: [pledgeId] } } }
                """);
        Files.writeString(src.resolve("Broken.java"), """
                package x;

                import dev.logast.support.MdcScope;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                class Broken {
                    private static final Logger log = LoggerFactory.getLogger(Broken.class);

                    void publish(String pledgeId) {
                        try (var mdc = MdcScope.of("pledgeId", pledgeId)) {
                            log.info("Pledge publication started");
                            send(pledgeId);
                            log.info("Pledge publication completed");
                            log.info("Pledge publication completed");
                        }
                    }

                    private void send(String pledgeId) {
                        log.info("Pledge sent [pledgeId={}]", pledgeId);
                        try (var mdc = MdcScope.of("pledgeId", pledgeId)) {
                            log.debug("Pledge handed over");
                        }
                    }
                }
                """);
        Analysis a = Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null));
        List<String> v = Invariants.check(a, List.of("src/main/java/x/Broken.java"));
        assertThat(v).anySatisfy(s -> assertThat(s).contains("logs pledgeId in the message although MDC carries it"))
                .anySatisfy(s -> assertThat(s).contains("scopes pledgeId although it is in MDC already"))
                .anySatisfy(s -> assertThat(s).contains("Broken.publish logs 'completed' 2 times"));
    }
}
