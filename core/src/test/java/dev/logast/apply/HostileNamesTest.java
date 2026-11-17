package dev.logast.apply;

import dev.logast.Fixtures;
import dev.logast.Logast;
import dev.logast.orders.ScanResult;
import dev.logast.orders.WorkOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #58 d: user code that already uses the names logast generates ({@code mdc}, {@code result}, {@code error},
 * {@code observed}, {@code t}, {@code rows}) is migrated the same way as code that does not. The same small
 * service is written twice, once with neutral names and once with the hostile ones; both are applied until nothing is
 * left, both must compile, no order may be rejected, and the results must be the same modulo the renaming (a generated
 * name that had to step aside gets a number: {@code result2}).
 *
 * <p>A local named {@code log} is a known gap ({@link #aLocalNamedLogHidesTheGeneratedLogger}).
 *
 * <p>Catch parameters named {@code _} (Java 22+) are E2eGoldenTest#unnamedCatchParametersAreNotPassedOn (#44): an
 * unnamed exception cannot be passed on, so its result differs from a named one by design.
 */
class HostileNamesTest {
    /** neutral name → hostile name: locals, parameters, a field, a catch parameter. */
    private static final Map<String, String> NAMES = new LinkedHashMap<>();

    static {
        NAMES.put("outcome", "result");      // local next to the async error handler's (result, error) lambda
        NAMES.put("fault", "error");
        NAMES.put("labels", "mdc");          // a field the MDC scope's body reads
        NAMES.put("pending", "mdc");         // a local inside the wrapped body
        NAMES.put("cause", "t");             // catch parameter
        NAMES.put("seen", "observed");       // locals inside an observation wrap
        NAMES.put("item", "t");
        NAMES.put("total", "rows");          // locals next to the JdbcTemplate outcome variables
        NAMES.put("query", "result");
    }

    /** Generated names that step aside get a number. */
    private static final String GENERATED = "\\b(result|error|mdc|t|observed|rows)\\d+\\b";

    @TempDir
    Path tmp;

    private static final String POLICY = """
            packs: [logging, tracing, metrics, resilience, errors, audit]
            support:
              mode: vendor
            vocabulary:
              order:
                mdc:
                  key: orderId
                  sources:
                    params: [orderId]
            """;

    private static final Map<String, String> SOURCES = Map.of(
            "ParcelController", """
                    package com.example.parcel;

                    import java.util.Map;
                    import java.util.concurrent.CompletableFuture;
                    import java.util.concurrent.ConcurrentHashMap;
                    import org.slf4j.Logger;
                    import org.slf4j.LoggerFactory;
                    import org.springframework.web.bind.annotation.GetMapping;
                    import org.springframework.web.bind.annotation.PathVariable;
                    import org.springframework.web.bind.annotation.RestController;

                    @RestController
                    public class ParcelController {
                        private static final Logger log = LoggerFactory.getLogger(ParcelController.class);
                        private final Map<String, String> labels = new ConcurrentHashMap<>();

                        @GetMapping("/parcels/{orderId}")
                        public String parcel(@PathVariable String orderId) {
                            String outcome = labels.getOrDefault(orderId, "none");
                            String fault = outcome.isEmpty() ? "empty" : "";
                            log.info("Parcel looked up [orderId={}, fault={}]", orderId, fault);
                            CompletableFuture.runAsync(() -> System.out.println(outcome));
                            return outcome;
                        }

                        @GetMapping("/parcels/{orderId}/label")
                        public String label(@PathVariable String orderId) {
                            int pending = orderId.length();
                            try {
                                return orderId.substring(pending / 2).trim();
                            } catch (RuntimeException cause) {
                                log.warn("Parcel label failed [orderId={}, pending={}]", orderId, pending);
                                return "";
                            }
                        }
                    }
                    """,
            "AckListener", """
                    package com.example.parcel;

                    import java.util.List;
                    import org.slf4j.Logger;
                    import org.slf4j.LoggerFactory;
                    import org.springframework.kafka.annotation.KafkaListener;
                    import org.springframework.stereotype.Component;

                    @Component
                    public class AckListener {
                        private static final Logger log = LoggerFactory.getLogger(AckListener.class);

                        @KafkaListener(topics = "parcel-acks", batch = "true")
                        public void onAcks(List<String> payloads) {
                            payloads = payloads.subList(0, Math.min(10, payloads.size()));
                            int seen = 0;
                            for (String item : payloads) {
                                seen += item.length();
                            }
                            log.info("Parcel acks received [count={}, seen={}]", payloads.size(), seen);
                        }
                    }
                    """,
            "ParcelStore", """
                    package com.example.parcel;

                    import java.util.List;
                    import org.springframework.jdbc.core.JdbcTemplate;
                    import org.springframework.stereotype.Repository;

                    @Repository
                    public class ParcelStore {
                        private final JdbcTemplate jdbc;

                        public ParcelStore(JdbcTemplate jdbc) {
                            this.jdbc = jdbc;
                        }

                        public int close(String orderId) {
                            StringBuilder journal = new StringBuilder("close ").append(orderId);
                            int total = journal.length();
                            jdbc.update("UPDATE parcel SET closed = TRUE WHERE order_id = ?", orderId);
                            return total;
                        }

                        public List<String> open() {
                            String query = "SELECT order_id FROM parcel WHERE closed = FALSE";
                            return jdbc.queryForList(query, String.class);
                        }
                    }
                    """);

    @Test
    void hostileNamesMigrateLikeNeutralOnes() throws IOException {
        Map<String, String> neutral = migrate("neutral", SOURCES, null);
        assertThat(compileErrors(tmp.resolve("neutral"))).as("the neutral service compiles after the migration").isEmpty();
        Map<String, String> hostileSources = new TreeMap<>();
        SOURCES.forEach((file, text) -> hostileSources.put(file, rename(text)));
        Map<String, String> hostile = migrate("hostile", hostileSources, null);
        assertThat(compileErrors(tmp.resolve("hostile"))).as("the hostile service compiles after the migration").isEmpty();

        assertThat(neutral).as("the neutral service is changed by the migration")
                .allSatisfy((file, text) -> assertThat(text).isNotEqualTo(SOURCES.get(file)));
        Map<String, String> expected = new TreeMap<>();
        neutral.forEach((file, text) -> expected.put(file, normalise(rename(text))));
        Map<String, String> actual = new TreeMap<>();
        hostile.forEach((file, text) -> actual.put(file, normalise(text)));
        assertThat(actual).as("hostile names give the neutral result, renamed").isEqualTo(expected);
    }

    /**
     * Known gap (#58 d): a local {@code log} that is not a logger, in a method where an outcome log line is inserted.
     * The line is written as {@code log.info(..)}, and the local hides the logger field logast adds. The compile gate
     * (#44) rejects the order, so the code stays as it was, but the file's other outcome logs are rejected with it.
     * When this test fails because nothing is rejected, add {@code journal → log} to {@link #NAMES} and delete it.
     */
    @Test
    void aLocalNamedLogHidesTheGeneratedLogger() throws IOException {
        Map<String, String> rejected = new TreeMap<>();
        Map<String, String> hostile = migrate("log-local", Map.of("ParcelStore",
                SOURCES.get("ParcelStore").replaceAll("\\bjournal\\b", "log")), rejected);
        assertThat(rejected).as("#58 d: still the known clash, rejected by the compile gate").isNotEmpty()
                .allSatisfy((id, why) -> assertThat(why).contains("variable log of type java.lang.StringBuilder"));
        assertThat(hostile.get("ParcelStore")).as("the rejected orders left the file alone").doesNotContain("log.info(")
                .doesNotContain("log.debug(");
        assertThat(compileErrors(tmp.resolve("log-local"))).isEmpty();
    }

    private static String rename(String text) {
        for (Map.Entry<String, String> e : NAMES.entrySet()) {
            text = text.replaceAll("\\b" + e.getKey() + "\\b", e.getValue());
        }
        return text;
    }

    private static String normalise(String text) {
        return text.replaceAll(GENERATED, "$1");
    }

    /**
     * Writes the service (which must compile as written), applies until no order with edits is left and no order is
     * rejected (or, with {@code rejected}, collects the rejected orders there); returns the migrated sources.
     */
    private Map<String, String> migrate(String name, Map<String, String> sources, Map<String, String> rejected)
            throws IOException {
        Path root = tmp.resolve(name);
        Files.createDirectories(root.resolve("src/main/java/com/example/parcel"));
        Files.createDirectories(root.resolve("src/main/resources"));
        Files.writeString(root.resolve("logast.yaml"), POLICY);
        Files.writeString(root.resolve("src/main/resources/application.yml"), "spring:\n  application:\n    name: parcels\n");
        Files.writeString(root.resolve("build.gradle.kts"), """
                plugins { java }

                dependencies {
                    implementation("org.springframework.boot:spring-boot-starter-webmvc")
                    implementation("org.springframework:spring-jdbc")
                }
                """);
        // the build file and the application class decide whether and where the support classes are vendored
        Files.writeString(root.resolve("src/main/java/com/example/parcel/ParcelApplication.java"), """
                package com.example.parcel;

                @org.springframework.boot.autoconfigure.SpringBootApplication
                public class ParcelApplication {
                }
                """);
        for (Map.Entry<String, String> f : sources.entrySet()) {
            Files.writeString(root.resolve("src/main/java/com/example/parcel/" + f.getKey() + ".java"), f.getValue());
        }
        assertThat(compileErrors(root)).as(name + " compiles as written").isEmpty();
        deleteTree(root.resolve("out"));

        Applier applier = new Applier(this::scan, new FillValidator(Logast.policy(root, null)))
                .compileWith(Fixtures.classpath());   // the compile gate, as the CLI runs it (#44)
        ScanResult last = scan(root);
        for (int pass = 0; pass < 3 && last.orders().stream().anyMatch(o -> !o.reportOnly() && o.slots().isEmpty()); pass++) {
            Applier.Result r = applier.apply(root, new Applier.Selection(true, Set.of(), Map.of()), true);
            if (rejected == null) {
                assertThat(r.rejected()).as(name + ": no order is rejected (a generated name clashing with one in the code)")
                        .isEmpty();
            } else {
                rejected.putAll(r.rejected());
            }
            last = scan(root);
        }
        if (rejected == null) {
            assertThat(last.orders()).as(name + ": only report-only orders and texts to write remain")
                    .allMatch(o -> o.reportOnly() || !o.slots().isEmpty());
        }

        Map<String, String> out = new TreeMap<>();
        for (String file : sources.keySet()) {
            out.put(file, Files.readString(root.resolve("src/main/java/com/example/parcel/" + file + ".java")));
        }
        return out;
    }

    private ScanResult scan(Path root) {
        return Logast.scan(root, Fixtures.classpath(), Logast.policy(root, null));
    }

    /** javac's errors for the project's sources, empty when it compiles. */
    private static String compileErrors(Path root) throws IOException {
        List<String> sources;
        try (Stream<Path> s = Files.walk(root.resolve("src/main/java"))) {
            sources = s.filter(p -> p.toString().endsWith(".java")).map(Path::toString).toList();
        }
        List<String> args = new ArrayList<>(List.of("-d", root.resolve("out").toString(), "-proc:none", "-cp",
                String.join(java.io.File.pathSeparator, Fixtures.classpath().stream().map(Path::toString).toList())));
        args.addAll(sources);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = ToolProvider.getSystemJavaCompiler().run(null, null, err, args.toArray(String[]::new));
        return rc == 0 ? "" : err.toString();
    }

    private static void deleteTree(Path root) throws IOException {
        if (Files.exists(root)) {
            try (Stream<Path> s = Files.walk(root)) {
                for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
    }
}
