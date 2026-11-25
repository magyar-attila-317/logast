package dev.logast;

import dev.logast.rules.MetricsMap;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** logast metrics: what is measured, by what, and which logged outcomes no metric counts. */
class MetricsMapTest {
    @Test
    void mapsEntrypointsMetersAndUncountedOutcomes() throws Exception {
        MetricsMap.Map m = MetricsMap.of(Analysis.run(Fixtures.root(), Fixtures.classpath(), Fixtures.policy()));
        assertThat(m.measured()).anySatisfy(x -> assertThat(x.query()).startsWith("http.server.requests{method=\"POST\""));
        assertThat(m.measured()).anySatisfy(x -> assertThat(x.query()).startsWith("tasks.scheduled.execution{"));
        assertThat(m.unmeasuredEntrypoints()).anySatisfy(e -> assertThat(e).startsWith("event "));
        assertThat(m.outcomes()).anySatisfy(u -> {
            assertThat(u.message()).isEqualTo("Audit skipped");
            assertThat(u.event()).isEqualTo("skipped");
            assertThat(u.counter()).isEqualTo("Counter.builder(\"audit\").tag(\"outcome\", \"skipped\").register(meterRegistry).increment()");
        });
        assertThat(m.identity()).containsEntry("spring.application.name", "shop");
    }

    /** #324 M4: code active only in a test or seed profile, or in a test-data package, gets no counter. */
    @Test
    void testOnlyCodeIsNotMeasured(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path src = java.nio.file.Files.createDirectories(dir.resolve("src/main/java/x"));
        String seeder = """
                package PKG;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.context.annotation.Profile;
                import org.springframework.stereotype.Component;

                @Component
                PROFILE
                public class NAME {
                    private static final Logger log = LoggerFactory.getLogger(NAME.class);

                    public void run() {
                        log.info("Tenant seed completed");
                    }
                }
                """;
        java.nio.file.Files.writeString(src.resolve("E2eSeeder.java"), seeder.replace("PKG", "x").replace("PROFILE", "@Profile({\"e2e\", \"local\"})").replace("NAME", "E2eSeeder"));
        java.nio.file.Files.writeString(src.resolve("ProdSeeder.java"), seeder.replace("PKG", "x").replace("PROFILE", "@Profile(\"!test\")").replace("NAME", "ProdSeeder"));
        java.nio.file.Path data = java.nio.file.Files.createDirectories(dir.resolve("src/main/java/x/testdata"));
        java.nio.file.Files.writeString(data.resolve("Fixtures.java"), seeder.replace("PKG", "x.testdata").replace("PROFILE", "").replace("NAME", "Fixtures"));
        // #326 M4: a profile named by a constant, and conditions on e2e / dev properties
        java.nio.file.Files.writeString(src.resolve("ThingSeeder.java"), seeder.replace("PKG", "x")
                .replace("PROFILE", "@Profile(ThingSeeder.PROFILE)").replace("NAME", "ThingSeeder")
                .replace("    private static final Logger log", "    static final String PROFILE = \"e2e-tenant\";\n    private static final Logger log"));
        java.nio.file.Files.writeString(src.resolve("E2eBootstrap.java"), seeder.replace("PKG", "x")
                .replace("PROFILE", "@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = \"e2e.enabled\", havingValue = \"true\")")
                .replace("NAME", "E2eBootstrap"));
        java.nio.file.Files.writeString(src.resolve("DevLogin.java"), seeder.replace("PKG", "x")
                .replace("PROFILE", "@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(\"${dev.login.enabled:false} and !${e2e.enabled:false}\")")
                .replace("NAME", "DevLogin"));
        MetricsMap.Map m = MetricsMap.of(Analysis.run(dir, Fixtures.classpath(), dev.logast.policy.Policy.resolve(dir, null)));
        assertThat(m.outcomes()).extracting(MetricsMap.Unmeasured::method).containsExactly("ProdSeeder.run");
    }

    /** #327: a team's own non-production profile (qa) is a setting. */
    @Test
    void aTeamsOwnTestProfileIsASetting(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path src = java.nio.file.Files.createDirectories(dir.resolve("src/main/java/x"));
        java.nio.file.Files.writeString(src.resolve("QaSeeder.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.context.annotation.Profile;
                import org.springframework.stereotype.Component;

                @Component
                @Profile("qa")
                public class QaSeeder {
                    private static final Logger log = LoggerFactory.getLogger(QaSeeder.class);

                    public void run() {
                        log.info("Tenant seed completed");
                    }
                }
                """);
        assertThat(MetricsMap.of(Analysis.run(dir, Fixtures.classpath(), dev.logast.policy.Policy.resolve(dir, null))).outcomes())
                .as("qa is no built-in profile").isNotEmpty();
        java.nio.file.Files.writeString(dir.resolve("logast.yaml"), "metrics:\n  nonProductionProfiles: [qa]\n");
        assertThat(MetricsMap.of(Analysis.run(dir, Fixtures.classpath(), dev.logast.policy.Policy.resolve(dir, null))).outcomes())
                .as("listed: test-only").isEmpty();
    }
}
