package dev.logast.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End to end, the way a person migrates a service: {@code logast apply . --auto --write} on e2e/before (shapes real
 * services showed), then every file and the printed output must equal e2e/expected. The result must compile.
 * A second apply is recorded too: what it still changes shows up in the snapshot.
 * <p>After an intended behaviour change: {@code ./gradlew :cli:test -Pe2e.update}, then review the diff of e2e/expected.
 */
class E2eGoldenTest {
    private static final Path BEFORE = Path.of(System.getProperty("logast.e2e.before"));
    private static final Path EXPECTED = Path.of(System.getProperty("logast.e2e.expected"));
    /** The fixture's classpath plus Lombok (real services use it): written once per run into a temp file. */
    private static final String CLASSPATH = e2eClasspath();
    private static final String LOMBOK = System.getProperty("logast.e2e.lombok", "");
    /**
     * e2e/platform compiled: a library the service compiles against but logast never sees (its types stay unresolved
     * in the analysis, as with an internal jar missing from a copied classpath). Only javac gets it.
     */
    private static final Path PLATFORM = platformClasses();

    private static Path platformClasses() {
        try {
            Path out = Files.createTempDirectory("logast-e2e-platform");
            List<String> args = new ArrayList<>(List.of("-d", out.toString()));
            try (Stream<Path> s = Files.walk(BEFORE.resolveSibling("platform"))) {
                s.filter(p -> p.toString().endsWith(".java")).map(Path::toString).forEach(args::add);
            }
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int rc = ToolProvider.getSystemJavaCompiler().run(null, null, err, args.toArray(String[]::new));
            if (rc != 0) {
                throw new IllegalStateException("e2e/platform does not compile:\n" + err);
            }
            return out;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String e2eClasspath() {
        try {
            List<String> lines = new ArrayList<>(Files.readAllLines(Path.of(System.getProperty("logast.e2e.classpath"))));
            for (String p : System.getProperty("logast.e2e.lombok", "").split(java.io.File.pathSeparator)) {
                if (!p.isBlank()) {
                    lines.add(p);
                }
            }
            Path f = Files.createTempFile("logast-e2e-classpath", ".txt");
            Files.write(f, lines);
            f.toFile().deleteOnExit();
            return f.toString();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
    private static final boolean UPDATE = Boolean.getBoolean("logast.e2e.update");
    /** Generated each run or machine-specific: not part of the snapshot. */
    private static final Set<String> SKIP = Set.of(".logast/orders.json", ".logast/classpath.txt", ".logast/classpath-root.txt");

    @TempDir
    Path tmp;

    @Test
    void migrationMatchesTheGoldenSnapshot() throws IOException {
        golden(EXPECTED, migrate(tmp.resolve("app"), CLASSPATH, app -> {
        }));
    }

    /** Every file equals the snapshot in {@code expectedDir}; with -Pe2e.update the snapshot is rewritten instead. */
    private static void golden(Path expectedDir, Map<String, String> actual) throws IOException {
        if (UPDATE) {
            deleteTree(expectedDir);
            for (var e : actual.entrySet()) {
                Path p = expectedDir.resolve(e.getKey());
                Files.createDirectories(p.getParent());
                Files.writeString(p, e.getValue());
            }
            return;
        }
        Map<String, String> expected = files(expectedDir);
        List<String> problems = new ArrayList<>();
        new TreeSet<>(expected.keySet()).stream().filter(k -> !actual.containsKey(k)).forEach(k -> problems.add("missing: " + k));
        new TreeSet<>(actual.keySet()).stream().filter(k -> !expected.containsKey(k)).forEach(k -> problems.add("new file: " + k));
        for (String k : new TreeSet<>(actual.keySet())) {
            if (expected.containsKey(k) && !expected.get(k).equals(actual.get(k))) {
                problems.add(diff(k, expected.get(k), actual.get(k)));
            }
        }
        assertThat(problems).as(expectedDir.getFileName() + " differs (run ./gradlew :cli:test -Pe2e.update if the change is "
                + "intended, then review `git diff e2e`)").isEmpty();
    }

    /**
     * The result must not depend on what else sits on the classpath: class folders switched on an incremental re-parse
     * once, which lost calls through interfaces and base classes in later rounds (scopes in every validator). The
     * service's own compiled classes (a build ran before), and class folders that do not match the sources (a copied
     * classpath), must give exactly the golden files.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"own compiled classes", "foreign class folder"})
    void classFoldersOnTheClasspathChangeNothing(String variant) throws IOException {
        Path classes = tmp.resolve("classes");
        if (variant.startsWith("own")) {
            Path pre = tmp.resolve("pre");
            copy(BEFORE, pre);
            compile(pre);
            classes = pre.resolve("out");
        } else {
            Files.createDirectories(classes.resolve("other"));
            Files.write(classes.resolve("other/Unrelated.class"), new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
        }
        List<String> cp = new ArrayList<>(Files.readAllLines(Path.of(CLASSPATH)));
        cp.add(classes.toString());
        Path cpFile = tmp.resolve("classpath.txt");
        Files.write(cpFile, cp);
        Map<String, String> actual = migrate(tmp.resolve("app"), cpFile.toString(), app -> {
        });
        // what went wrong on a real service: the checks, reached only through CouponCheck.validate, lost their caller
        // in later rounds and got scopes of their own although the calculator's scope already covers them
        assertThat(actual.get("src/main/java/com/example/e2e/income/CouponChecks.java"))
                .as("checks called through the interface are covered by the calculator's scope").doesNotContain("MdcScope");
        assertSameFiles(files(EXPECTED), actual);
    }

    /**
     * A service that names itself nowhere, with the "spring:" key left empty: it is named after its folder (the
     * project's, not the scratch copy apply works in, which gave "logast-apply-1234…", a new name every run), and the
     * empty key is filled instead of kept. Every file must equal e2e/expected-unnamed.
     */
    @Test
    void anUnnamedServiceIsNamedAfterItsFolder() throws IOException {
        golden(EXPECTED.resolveSibling("expected-unnamed"), migrate(tmp.resolve("billing-service"), CLASSPATH, app -> {
            Path yml = app.resolve("src/main/resources/application.yml");
            String text = Files.readString(yml);
            String unnamed = text.replaceFirst("(?m)^ {2}application:\\n {4}name: e2e-shop\\n", "");
            assertThat(unnamed).as("the scenario's name was removed").isNotEqualTo(text).startsWith("spring:\n");
            Files.writeString(yml, unnamed);
        }));
    }

    /**
     * The whole process: migrate, then write every message text logast asks for (fills.json) and count every outcome
     * (metrics.json), then apply once more. Every decision must land, also the ones on lines the texts reword (their
     * ids used to include the message text, so a reworded line lost its decision without a word). Every file must
     * equal e2e/expected-decided.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "metrics.catalog: {0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"per-package", "single", "off"})
    void filledTextsAndMetricDecisionsAllLand(String catalog) throws IOException {
        Path app = tmp.resolve("app");
        migrate(app, CLASSPATH, a -> {
            // the manual flow: nothing counted by default, every outcome decided in metrics.json
            Path yml = a.resolve("logast.yml");
            Files.writeString(yml, Files.readString(yml) + "metrics:\n  defaults: []\n"
                    + (catalog.equals("per-package") ? "" : "  catalog: " + catalog + "\n"));   // per-package is the default
        });
        writeTexts(app);
        Path metrics = app.resolve(".logast/metrics.json");
        String m = Files.readString(metrics);
        assertThat(m).as("metrics.json has outcomes to decide").contains("\"count\" : \"\"");
        // count every outcome; where the line logs a number, count that too: the first entry per run, the rest totals
        String counted0 = m.replace("\"count\" : \"\"", "\"count\" : \"counter\"");
        java.util.regex.Matcher am = java.util.regex.Pattern.compile("\"amount\" : \"\",(\\s*)\"as\" : \"\",(\\s*)\"_amounts\" : \\[ \"(\\w+)\"")
                .matcher(counted0);
        StringBuilder sb = new StringBuilder();
        int amounts = 0;
        while (am.find()) {
            am.appendReplacement(sb, "\"amount\" : \"$3\",$1\"as\" : \"" + (amounts++ == 0 ? "per-run" : "") + "\",$2\"_amounts\" : [ \"$3\"");
        }
        am.appendTail(sb);
        assertThat(amounts).as("some outcome lines log a number to count").isPositive();
        Files.writeString(metrics, sb.toString());
        long decided = m.lines().filter(l -> l.contains("\"count\" : \"\"")).count();

        String third = run(app, "apply", app.toString(), "--auto", "--write", "--classpath-file", CLASSPATH);
        Files.writeString(app.resolve("OUTPUT.txt"), Files.readString(app.resolve("OUTPUT.txt"))
                + "\n=== after writing every text and counting every outcome\n" + third);
        compile(app);
        Map<String, String> actual = files(app);
        // every decision is a counting line: inline (catalog off) or one call into a catalog, whose member holds the name
        long counted = actual.entrySet().stream().filter(e -> e.getKey().startsWith("src/")).map(Map.Entry::getValue)
                .mapToLong(t -> t.lines().filter(l -> l.contains("Outcomes.count(\"") || l.contains("Outcomes.record(\"")).count()).sum();
        long withAmount = actual.entrySet().stream().filter(e -> e.getKey().startsWith("src/")).map(Map.Entry::getValue)
                .mapToLong(t -> t.lines().filter(l -> l.matches(".*Outcomes\\.(count|record)\\(\"[^\"]+\", \"[^\"]+\", \"[^\"]+\", [^\"]+\\);.*")).count()).sum();
        // decisions that cannot be written stay in metrics.json with a "_problem" (e.g. a log line inside a returned pipeline)
        long problems = actual.get(".logast/metrics.json").lines().filter(l -> l.contains("\"_problem\"")).count();
        // the reactive pipeline's line (inside a return), the async failure line (inside whenComplete's if) and the line in
        // runAsync's lambda (another thread, later) cannot be counted after their statement
        assertThat(problems).as("lines that cannot be counted after their statement").isEqualTo(3);
        decided -= problems;
        if (catalog.equals("off")) {
            assertThat(counted).as("every outcome decided in metrics.json is counted").isEqualTo(decided);
            assertThat(withAmount).as("every amount named in metrics.json is counted").isEqualTo(amounts);
        } else {
            List<String> catalogs = actual.entrySet().stream().filter(e -> e.getValue().contains("// logast:metrics-catalog"))
                    .map(Map.Entry::getKey).toList();
            assertThat(catalogs).as("catalog classes").isNotEmpty();
            if (catalog.equals("single")) {
                assertThat(catalogs).containsExactly("src/main/java/com/example/e2e/metrics/BusinessMetrics.java");
            }
            long outside = actual.entrySet().stream().filter(e -> e.getKey().startsWith("src/") && !catalogs.contains(e.getKey())
                            && !e.getKey().contains("/logging/"))
                    .mapToLong(e -> e.getValue().lines().filter(l -> l.matches(".*(Outcomes\\.(count|record)|ObservedContext\\.entrypoint)\\(\".*")).count())
                    .sum();
            assertThat(outside).as("no metric or observation name is left at a call site").isZero();
            long calls = actual.entrySet().stream().filter(e -> e.getKey().startsWith("src/") && !catalogs.contains(e.getKey()))
                    .mapToLong(e -> e.getValue().lines().filter(l -> l.matches("\\s*\\w*Metrics\\.[a-z]\\w*\\(.*\\);.*")).count()).sum();
            assertThat(calls).as("every outcome decided in metrics.json is counted through the catalog").isEqualTo(decided);
        }
        golden(EXPECTED.resolveSibling(catalog.equals("per-package") ? "expected-decided" : "expected-decided-" + catalog), actual);
    }

    /**
     * metrics.amounts: off in logast.yaml: metrics.json offers no amounts, and the default database counts carry no row
     * amount. Every file must equal e2e/expected-amounts-off.
     */
    @Test
    void amountsOffOffersNoAmountsAndChangesNothingElse() throws IOException {
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, app -> {
            Path yml = app.resolve("logast.yml");
            Files.writeString(yml, Files.readString(yml) + "metrics:\n  amounts: off\n");
        });
        assertThat(actual.get(".logast/metrics.json")).as("no amounts offered").doesNotContain("\"_amounts\"");
        assertThat(actual.values().stream().filter(t -> t.contains("Outcomes.count(\"") && t.contains("// logast:metrics-catalog")).toList())
                .as("the default database count has no row amount").noneMatch(t -> t.contains("\"rows\""));
        golden(EXPECTED.resolveSibling("expected-amounts-off"), actual);
    }

    /**
     * Lombok not on logast's classpath (missing from a copied classpath; the service still compiles with it): @Slf4j
     * loggers do not resolve. Their log calls are recognised by name, so rules see the lines they add (they used to add
     * them again on every pass), new loggers are still @Slf4j (the sources import it), apply converges in one run and
     * warns. Every file must equal e2e/expected: the same result as with Lombok, plus the warning.
     */
    @Test
    void withoutLombokOnTheClasspathItConvergesAndWarns() throws IOException {
        List<String> cp = Files.readAllLines(Path.of(CLASSPATH)).stream().filter(l -> !l.contains("lombok")).toList();
        assertThat(cp).as("the e2e classpath had Lombok").hasSizeLessThan(Files.readAllLines(Path.of(CLASSPATH)).size());
        Path cpFile = tmp.resolve("classpath-nolombok.txt");
        Files.write(cpFile, cp);
        Map<String, String> actual = migrate(tmp.resolve("app"), cpFile.toString(), app -> {
        });
        assertThat(actual.get("OUTPUT.txt")).as("apply says the loggers did not resolve").contains("recognised by name only");
        Map<String, String> withoutWarning = new TreeMap<>(actual);
        withoutWarning.put("OUTPUT.txt", actual.get("OUTPUT.txt").lines().filter(l -> !l.contains("recognised by name only"))
                .collect(java.util.stream.Collectors.joining("\n", "", "\n")));
        assertSameFiles(files(EXPECTED), withoutWarning);
    }

    /** Writes a text ("Coupon sweep <event>") into every fills.json entry that asks for one in a lifecycle form. */
    private static void writeTexts(Path app) throws IOException {
        Path fills = app.resolve(".logast/fills.json");
        String f = Files.readString(fills);
        String filledTexts = java.util.regex.Pattern.compile("\"MSG\" : \"\",(\\s*)\"_MSG\" : \"([^\"]*?must read as: \\{subject} (\\w+)[^\"]*)\"")
                .matcher(f).replaceAll("\"MSG\" : \"Coupon sweep $3\",$1\"_MSG\" : \"$2\"");
        assertThat(filledTexts).as("fills.json has texts to write").isNotEqualTo(f);
        Files.writeString(fills, filledTexts);
    }

    /**
     * Texts are written, then a log line is added above them in the same method (by hand, or a later logast run):
     * the ids of the lines below shift. Each text must still land on the line it was written for, never on another
     * one. Every file must equal e2e/expected-shifted.
     */
    @Test
    void writtenTextsFollowTheirLineWhenItsIdShifts() throws IOException {
        Path app = tmp.resolve("app");
        migrate(app, CLASSPATH, a -> {
        });
        writeTexts(app);
        // the reworded line in PaymentClient.charge is keyed by its place among the method's log calls
        Path fills = app.resolve(".logast/fills.json");
        String f = Files.readString(fills);
        String reworded = java.util.regex.Pattern.compile("\"MSG\" : \"\",(\\s*\"_MSG\" : \"Reword this DEBUG message[^\"]*was: Charging order)")
                .matcher(f).replaceFirst("\"MSG\" : \"Charging the card\",$1");
        assertThat(reworded).as("fills.json asks to reword the charge line").isNotEqualTo(f);
        Files.writeString(fills, reworded);
        Path job = app.resolve("src/main/java/com/example/e2e/coupon/CouponCleanupJob.java");
        String text = Files.readString(job);
        String shifted = text.replace("    public void cleanup() {\n", "    public void cleanup() {\n        log.debug(\"Cleanup tick\");\n");
        assertThat(shifted).as("the line is added").isNotEqualTo(text);
        Files.writeString(job, shifted);
        Path client = app.resolve("src/main/java/com/example/e2e/order/PaymentClient.java");
        String c = Files.readString(client);
        String cShifted = c.replace("            try {\n                log.debug(\"Charging order", "            try {\n                log.debug(\"Charge requested\");\n                log.debug(\"Charging order");
        assertThat(cShifted).as("a line above the reworded one").isNotEqualTo(c);
        Files.writeString(client, cShifted);

        String third = run(app, "apply", app.toString(), "--auto", "--write", "--classpath-file", CLASSPATH);
        Files.writeString(app.resolve("OUTPUT.txt"), Files.readString(app.resolve("OUTPUT.txt"))
                + "\n=== after writing the texts and adding a line above them\n" + third);
        compile(app);
        Map<String, String> actual = files(app);
        String result = actual.get("src/main/java/com/example/e2e/coupon/CouponCleanupJob.java");
        assertThat(result).as("each text on its own line").contains("log.info(\"Coupon sweep started\")")
                .contains("log.info(\"Coupon sweep completed [couponCount={}]\"").contains("log.debug(\"Cleanup tick\")");
        assertThat(actual.get("src/main/java/com/example/e2e/order/PaymentClient.java")).as("the reworded line, not the one above it")
                .contains("log.debug(\"Charge requested\");").contains("log.debug(\"Charging the card [amountCents={}]\"");
        golden(EXPECTED.resolveSibling("expected-shifted"), actual);
    }

    /**
     * init on a service without logast.yaml: everything it writes, and its output, which spells out the whole flow
     * (apply, fill in the files apply creates, apply again on the migrated code, review). The source files stay as they
     * are. Every file init writes must equal e2e/expected-init.
     */
    @Test
    void initWritesItsFilesAndSpellsOutTheFlow() throws IOException {
        Path app = tmp.resolve("app");
        copy(BEFORE, app);
        Files.delete(app.resolve("logast.yml"));
        Map<String, String> before = files(app);
        String output = run(app, "init", app.toString(), "--no-classpath");
        Files.writeString(app.resolve("OUTPUT.txt"), output);
        assertThat(output).as("the flow, in order").containsSubsequence("logast apply . --auto --write",
                "fill in .logast/fills.json and .logast/metrics.json", "logast apply . --auto --write", "git diff");
        Map<String, String> written = new TreeMap<>();
        files(app).forEach((k, v) -> {
            if (!v.equals(before.get(k))) {
                written.put(k, v);
            }
        });
        assertThat(written.keySet()).as("init changes no source file").noneMatch(k -> k.startsWith("src/"));
        assertThat(written).as("init does not create the files to fill in").doesNotContainKeys(".logast/fills.json", ".logast/metrics.json");
        golden(EXPECTED.resolveSibling("expected-init"), written);
    }

    /**
     * Every pack works on its own: with only that pack selected, the run still converges in one pass, a second apply
     * changes nothing, no CHECK FAILED, and the result compiles (all checked by migrate). A pack that needs a support
     * class brings it along (errors: ProblemTraceId, without the logging set-up).
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "packs: [{0}]")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"logging", "tracing", "metrics", "resilience", "errors", "audit"})
    void eachPackWorksOnItsOwn(String pack) throws IOException {
        Path app = tmp.resolve("app");
        Map<String, String> actual = migrate(app, CLASSPATH, a -> {
            Path yml = a.resolve("logast.yml");
            String text = Files.readString(yml);
            String only = text.replaceFirst("(?m)^packs: .*$", "packs: [" + pack + "]");
            assertThat(only).as("the scenario selects its packs").isNotEqualTo(text);
            Files.writeString(yml, only);
        });
        if (pack.equals("errors")) {
            assertThat(actual.get("src/main/resources/application.yml")).contains("problem-trace-id:");
            assertThat(app.resolve("src/main/java/com/example/e2e/logging/ProblemTraceId.java"))
                    .as("the property comes with the class that reads it").exists();
            assertThat(app.resolve("src/main/java/com/example/e2e/logging/MdcScope.java"))
                    .as("but not with the logging set-up the pack did not ask for").doesNotExist();
        }
    }

    @Test
    void springBoot3UsesMicrometerTracingDependencies() throws IOException {
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, app -> {
            Path build = app.resolve("build.gradle.kts");
            String text = Files.readString(build);
            String boot3 = text.replace("plugins { java }", "plugins {\n    java\n    id(\"org.springframework.boot\") version \"3.5.7\"\n}");
            assertThat(boot3).as("the scenario declares the target Spring Boot version").isNotEqualTo(text);
            boot3 = boot3.replace("spring-boot-starter-webmvc", "spring-boot-starter-web")
                    .replace("dependencies {", "repositories { mavenCentral() }\n\ndependencies {\n"
                            + "    implementation(platform(\"org.springframework.boot:spring-boot-dependencies:3.5.7\"))");
            // a non-OpenTelemetry agent: once the OTel dependencies are added, it must not read as the OTel Java agent
            boot3 += "\ntasks.withType<Test>().configureEach { jvmArgs(\"-javaagent:/libs/mockito-core.jar\") }\n";
            Files.writeString(build, boot3);
        });

        String build = actual.get("build.gradle.kts");
        assertThat(build).contains("org.springframework.boot:spring-boot-starter-actuator")
                .contains("io.micrometer:micrometer-tracing-bridge-otel")
                .contains("io.opentelemetry:opentelemetry-exporter-otlp")
                .doesNotContain("spring-boot-starter-opentelemetry");
        assertThat(build.split("spring-boot-starter-actuator", -1)).hasSize(2);
        assertThat(build.split("micrometer-tracing-bridge-otel", -1)).hasSize(2);
        assertThat(build.split("opentelemetry-exporter-otlp", -1)).hasSize(2);
        assertThat(actual.get("FINDINGS.txt")).doesNotContain("Java agent");
        golden(EXPECTED.resolveSibling("expected-boot3"), actual);
    }

    @Test
    void springBoot3FlagsExistingConflictingOpenTelemetrySetup() throws IOException {
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, app -> {
            Path build = app.resolve("build.gradle.kts");
            String text = Files.readString(build);
            String boot3 = text.replace("plugins { java }", "plugins {\n    java\n    id(\"org.springframework.boot\") version \"3.5.7\"\n}")
                    .replace("spring-boot-starter-webmvc", "spring-boot-starter-web")
                    .replace("dependencies {", "repositories { mavenCentral() }\n\ndependencies {\n"
                            + "    implementation(platform(\"org.springframework.boot:spring-boot-dependencies:3.5.7\"))\n"
                            + "    implementation(\"org.springframework.boot:spring-boot-starter-opentelemetry\")\n"
                            + "    implementation(\"io.micrometer:micrometer-tracing-bridge-otel\")\n"
                            + "    implementation(\"io.micrometer:micrometer-tracing-bridge-brave\")\n"
                            + "    implementation(\"io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter\")");
            boot3 += "\ntasks.withType<JavaExec>().configureEach { jvmArgs(\"-javaagent:/opt/opentelemetry-javaagent.jar\") }\n";
            assertThat(boot3).as("the scenario declares the target Spring Boot version and conflicting integrations")
                    .isNotEqualTo(text);
            Files.writeString(build, boot3);
        });

        String build = actual.get("build.gradle.kts");
        assertThat(build).contains("spring-boot-starter-opentelemetry")
                .contains("micrometer-tracing-bridge-otel")
                .contains("micrometer-tracing-bridge-brave")
                .doesNotContain("spring-boot-starter-actuator")
                .doesNotContain("opentelemetry-exporter-otlp");
        assertThat(actual.get("FINDINGS.txt")).contains("Spring Boot 4 starter")
                .contains("only one tracer bridge")
                .contains("OpenTelemetry Java agent")
                .contains("OpenTelemetry Spring Boot starter is already present");
        golden(EXPECTED.resolveSibling("expected-boot3-conflict"), actual);
    }

    @Test
    void springBoot4UsesOpenTelemetryStarter() throws IOException {
        Path classpath = tmp.resolve("classpath-without-otel-starter.txt");
        Files.write(classpath, Files.readAllLines(Path.of(CLASSPATH)).stream()
                .filter(p -> {
                    String name = Path.of(p).getFileName().toString();
                    return !name.startsWith("spring-boot-starter-opentelemetry-")
                            && !name.startsWith("spring-boot-micrometer-tracing-opentelemetry-")
                            && !name.startsWith("micrometer-tracing-bridge-otel-")
                            && !name.startsWith("opentelemetry-exporter-otlp-");
                })
                .toList());
        Map<String, String> actual = migrate(tmp.resolve("app"), classpath.toString(), app -> {
            Path build = app.resolve("build.gradle.kts");
            String text = Files.readString(build);
            String boot4 = text.replace("plugins { java }", "plugins {\n    java\n    id(\"org.springframework.boot\") version \"4.1.2\"\n}")
                    .replace("dependencies {", "repositories { mavenCentral() }\n\ndependencies {\n"
                            + "    implementation(platform(\"org.springframework.boot:spring-boot-dependencies:4.1.2\"))");
            assertThat(boot4).as("the scenario declares the target Spring Boot version").isNotEqualTo(text);
            Files.writeString(build, boot4);
        });

        String build = actual.get("build.gradle.kts");
        assertThat(build).contains("org.springframework.boot:spring-boot-starter-opentelemetry")
                .doesNotContain("micrometer-tracing-bridge-otel");
        assertThat(build.split("spring-boot-starter-opentelemetry", -1)).hasSize(2);
        golden(EXPECTED.resolveSibling("expected-boot4"), actual);
    }

    @Test
    void removesCurrentClockFromBusinessEventAndKeepsBusinessTimestamp() throws IOException {
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, app -> {
            Path source = app.resolve("src/main/java/com/example/e2e/timestamp/RefundJob.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, """
                    package com.example.e2e.timestamp;

                    import java.time.LocalDateTime;
                    import java.time.OffsetDateTime;
                    import org.slf4j.Logger;
                    import org.slf4j.LoggerFactory;

                    class RefundJob {
                        private static final Logger log = LoggerFactory.getLogger(RefundJob.class);

                        void run(OffsetDateTime createdAt) {
                            log.info("Refund started [now={}]", LocalDateTime.now());
                            log.info("Refund completed [createdAt={}]", createdAt);
                        }
                    }
                    """);
        });

        String result = actual.get("src/main/java/com/example/e2e/timestamp/RefundJob.java");
        assertThat(result).contains("log.info(\"Refund started\")")
                .contains("log.info(\"Refund completed [createdAt={}]\", createdAt)")
                .doesNotContain("LocalDateTime.now()");
        golden(EXPECTED.resolveSibling("expected-business-timestamp"), actual);
    }

    @Test
    void marksFillAndMetricsReviewFindingsOnTheirLogStatements() throws IOException {
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, app -> {
            Path source = app.resolve("src/main/java/com/example/e2e/review/ReviewMarkers.java");
            Files.createDirectories(source.getParent());
            Path yml = app.resolve("logast.yml");
            Files.writeString(yml, Files.readString(yml) + "reporting:\n  sourceComments: true\n");   // off by default (#72)
            Files.writeString(source, """
                    package com.example.e2e.review;

                    import org.slf4j.Logger;
                    import org.slf4j.LoggerFactory;

                    class ReviewMarkers {
                        private static final Logger log = LoggerFactory.getLogger(ReviewMarkers.class);

                        void run(String value) {
                            log.warn("doing {} with something something", value);
                            log.info("Refund completed");
                        }
                    }
                    """);
        });

        String source = actual.get("src/main/java/com/example/e2e/review/ReviewMarkers.java");
        assertThat(source).contains("// logast: LOG_FORMAT ").contains("// logast: METRIC_OUTCOME_UNMEASURED ");
        assertThat(actual.get(".logast/fills.json")).contains("FMT-").contains("doing");
        assertThat(actual.get(".logast/metrics.json")).contains("OUM-").contains("_where");
        golden(EXPECTED.resolveSibling("expected-source-markers"), actual);
    }

    @Test
    void flagsInlineNamedPlaceholderWithoutRewordingItsMessage() throws IOException {
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, app -> {
            Path source = app.resolve("src/main/java/com/example/e2e/review/ScheduleCanceller.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, """
                    package com.example.e2e.review;

                    import org.slf4j.Logger;
                    import org.slf4j.LoggerFactory;

                    class ScheduleCanceller {
                        private static final Logger log = LoggerFactory.getLogger(ScheduleCanceller.class);

                        void cancel(String orderId) {
                            log.info("No schedules found for given orderId: {}. There was nothing to cancel.", orderId);
                        }
                    }
                    """);
        });

        String source = actual.get("src/main/java/com/example/e2e/review/ScheduleCanceller.java");
        assertThat(source).contains("log.info(\"No schedules found for given orderId: {}. There was nothing to cancel.\", orderId);\n").doesNotContain("// logast:");
        assertThat(actual.get(".logast/fills.json")).contains("FMT-").contains("orderId");
        assertThat(actual.get("FINDINGS.txt")).contains("No schedules found for given orderId");
        golden(EXPECTED.resolveSibling("expected-inline-named-placeholder"), actual);
    }

    /** #61: an ignore names rules exactly (METRIC_TAG_KEY is a prefix of METRIC_TAG_KEYS_MISMATCH); ignore-file covers a file. */
    @Test
    void suppressesOnlyTheRulesAnIgnoreNames() throws IOException {
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, app -> {
            Path yml = app.resolve("logast.yml");
            Files.writeString(yml, Files.readString(yml) + "metrics:\n  tags:\n    outcome: { aliases: [result] }\n");
            Path dir = app.resolve("src/main/java/com/example/e2e/suppress");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("ParcelCounters.java"), """
                    package com.example.e2e.suppress;

                    import io.micrometer.core.instrument.MeterRegistry;

                    class ParcelCounters {
                        private final MeterRegistry registry;

                        ParcelCounters(MeterRegistry registry) {
                            this.registry = registry;
                        }

                        void shipped() {
                            // logast:ignore METRIC_TAG_KEYS_MISMATCH
                            registry.counter("parcel.shipped", "result", "ok").increment();
                        }

                        void returned() {
                            registry.counter("parcel.returned", "result", "ok").increment(); // logast:ignore METRIC_TAG_KEY
                        }

                        void held() {
                            // logast:ignore METRIC_REGISTERED_PER_CALL, METRIC_TAG_KEY -- the tag is read by an old dashboard
                            registry.counter("parcel.held", "result", "ok").increment();
                        }

                        void lost() {
                            // logast:ignore
                            registry.counter("parcel.lost", "result", "ok").increment();
                        }
                    }
                    """);
            Files.writeString(dir.resolve("ParcelGauges.java"), """
                    package com.example.e2e.suppress;

                    // logast:ignore-file METRIC_TAG_KEY

                    import io.micrometer.core.instrument.MeterRegistry;

                    class ParcelGauges {
                        private final MeterRegistry registry;

                        ParcelGauges(MeterRegistry registry) {
                            this.registry = registry;
                        }

                        void sorted() {
                            registry.counter("parcel.sorted", "result", "ok").increment();
                        }
                    }
                    """);
        });

        String counters = actual.get("src/main/java/com/example/e2e/suppress/ParcelCounters.java");
        // the ignore names another rule: the tag key is still renamed
        assertThat(counters).contains("registry.counter(\"parcel.shipped\", \"outcome\", \"ok\")")
                .contains("registry.counter(\"parcel.returned\", \"result\", \"ok\")")
                .contains("registry.counter(\"parcel.held\", \"result\", \"ok\")")
                .contains("registry.counter(\"parcel.lost\", \"result\", \"ok\")");
        assertThat(actual.get("src/main/java/com/example/e2e/suppress/ParcelGauges.java"))
                .contains("registry.counter(\"parcel.sorted\", \"result\", \"ok\")");
        golden(EXPECTED.resolveSibling("expected-suppressions"), actual);
    }

    /** #69: a collection logged next to its own size is not rewritten to a second count, it is reported for rewording. */
    @Test
    void reportsCollectionWhoseSizeIsAlreadyLogged() throws IOException {
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, app -> {
            Path source = app.resolve("src/main/java/com/example/e2e/review/KeyRegistry.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, """
                    package com.example.e2e.review;

                    import java.util.ArrayList;
                    import java.util.List;
                    import java.util.Set;

                    import org.slf4j.Logger;
                    import org.slf4j.LoggerFactory;

                    class KeyRegistry {
                        private static final Logger log = LoggerFactory.getLogger(KeyRegistry.class);
                        private final List<String> items = new ArrayList<>();

                        List<String> getItems() {
                            return items;
                        }

                        void register(Set<String> existingKeys) {
                            log.warn("{} keys have an entity already associated with them: {}", existingKeys.size(), existingKeys);
                        }

                        void flush() {
                            log.info("Flushing {} pending items: {}", getItems().size(), getItems());
                        }
                    }
                    """);
        });

        String source = actual.get("src/main/java/com/example/e2e/review/KeyRegistry.java");
        assertThat(source)
                .contains("log.warn(\"{} keys have an entity already associated with them: {}\", existingKeys.size(), existingKeys);\n")
                .contains("log.info(\"Flushing {} pending items: {}\", getItems().size(), getItems());\n")
                .doesNotContain("existingKeys.size(), existingKeys.size()")
                .doesNotContain("getItems().size(), getItems().size()");
        assertThat(actual.get("FINDINGS.txt"))
                .contains("keys have an entity already associated with them")
                .contains("already logs its size (existingKeys.size())")
                .contains("Call: log.warn(\"{} keys have an entity already associated with them: {}\", existingKeys.size(), existingKeys)")
                .contains("already logs its size (getItems().size())");
        golden(EXPECTED.resolveSibling("expected-collection-size-logged"), actual);
    }

    /** apply twice (what one run leaves behind shows up), record the output, compile: every file of the result. */
    private Map<String, String> migrate(Path app, String classpath, IoAction prepare) throws IOException {
        copy(BEFORE, app);
        prepare.run(app);
        String first = run(app, "apply", app.toString(), "--auto", "--write", "--classpath-file", classpath);
        // the pipeline's steps settle in one pass: a second pass inside the same run applies nothing
        String trace = Files.readString(app.resolve(".logast/apply-trace.txt"));
        String afterPass1 = trace.contains("== pass 2") ? trace.substring(trace.indexOf("== pass 2")) : "";
        assertThat(afterPass1).as("pass 2 of the first apply applies nothing (.logast/apply-trace.txt):\n" + trace)
                .doesNotContain("applying");
        String second = run(app, "apply", app.toString(), "--auto", "--write", "--classpath-file", classpath);
        // one run is the end state: a second one finds nothing, and neither run trips a self-check
        assertThat(second).as("a second apply changes nothing").contains("applied (0):").contains("files (0):");
        assertThat(first + second).as("no CHECK FAILED (a rule that did not see its own fix, or a broken invariant)")
                .doesNotContain("CHECK FAILED");
        Files.writeString(app.resolve("OUTPUT.txt"), "=== logast apply . --auto --write\n" + first
                + "\n=== the same again (what one run left behind)\n" + second);
        // every finding left, with its text, location and path: report-only rules only show here (OUTPUT counts them)
        Files.writeString(app.resolve("FINDINGS.txt"), run(app, "scan", app.toString(), "--classpath-file", classpath));
        compile(app);
        return files(app);
    }

    interface IoAction {
        void run(Path app) throws IOException;
    }

    private static void assertSameFiles(Map<String, String> expected, Map<String, String> actual) {
        List<String> problems = new ArrayList<>();
        new TreeSet<>(expected.keySet()).stream().filter(k -> !actual.containsKey(k)).forEach(k -> problems.add("missing: " + k));
        new TreeSet<>(actual.keySet()).stream().filter(k -> !expected.containsKey(k)).forEach(k -> problems.add("new file: " + k));
        for (String k : new TreeSet<>(actual.keySet())) {
            if (expected.containsKey(k) && !expected.get(k).equals(actual.get(k))) {
                problems.add(diff(k, expected.get(k), actual.get(k)));
            }
        }
        assertThat(problems).as("differs from e2e/expected").isEmpty();
    }

    /**
     * A person rewords every line logast asked about (keeping the form it requires), then applies again: the result
     * must settle (a further apply changes nothing) and still pass the migration invariants.
     */
    /** logast shape: no business word leaves, the export compiles and gives the same findings as the original. */
    /**
     * Config fixes go where Spring Boot reads them (#47): .properties takes precedence over YAML in the same folder,
     * a profile-only document is not the base value, and a library module's application.yml is not the service's.
     */
    @Test
    void springConfigIsReadAndEditedWhereSpringReadsIt() throws IOException {
        // .properties sets the values: the fix changes them there (a YAML value would have no effect)
        Path app = tmp.resolve("props");
        copy(BEFORE, app);
        Path props = app.resolve("src/main/resources/application.properties");
        Files.writeString(props, "server.error.include-stacktrace=always\nserver.error.include-message=always\n#---\n"
                + "spring.config.activate.on-profile=dev\nserver.error.include-stacktrace=always\n");
        run(app, "apply", app.toString(), "--auto", "--write", "--classpath-file", CLASSPATH);
        assertThat(Files.readString(props)).as("changed in the base document of the file Spring reads")
                .startsWith("server.error.include-stacktrace=never\nserver.error.include-message=always\n#---\n")
                .endsWith("spring.config.activate.on-profile=dev\nserver.error.include-stacktrace=always\n");
        assertThat(Files.readString(app.resolve("src/main/resources/application.yml"))).doesNotContain("include-stacktrace");
        String settledProps = Files.readString(props);
        String settledYml = Files.readString(app.resolve("src/main/resources/application.yml"));
        run(app, "apply", app.toString(), "--auto", "--write", "--classpath-file", CLASSPATH);
        assertThat(Files.readString(props)).as("a second apply changes nothing").isEqualTo(settledProps);
        assertThat(Files.readString(app.resolve("src/main/resources/application.yml"))).isEqualTo(settledYml);
        String scan = run(app, "scan", app.toString(), "--classpath-file", CLASSPATH);
        assertThat(scan).as("include-message changes the response body: a decision, reported").contains("ERROR_DETAILS_EXPOSED")
                .contains("Decide on include-message").doesNotContain("Sets include-stacktrace");

        // #74: a later unconditional .properties document wins at runtime: the fix goes there, not into the first one
        Path later = tmp.resolve("props-later");
        copy(BEFORE, later);
        Path laterProps = later.resolve("src/main/resources/application.properties");
        Files.writeString(laterProps, "spring.application.name=sample\n#---\nserver.error.include-stacktrace=always\n#---\n"
                + "spring.config.activate.on-profile=dev\nserver.error.include-stacktrace=always\n");
        run(later, "apply", later.toString(), "--auto", "--write", "--classpath-file", CLASSPATH);
        assertThat(Files.readString(laterProps)).as("changed in the later document that sets it; profile document untouched")
                .isEqualTo("spring.application.name=sample\n#---\nserver.error.include-stacktrace=never\n#---\n"
                        + "spring.config.activate.on-profile=dev\nserver.error.include-stacktrace=always\n");
        run(later, "apply", later.toString(), "--auto", "--write", "--classpath-file", CLASSPATH);
        assertThat(Files.readString(laterProps)).as("a second apply changes nothing").contains("include-stacktrace=never\n#---")
                .doesNotContain("sample\nserver.error");
        assertThat(run(later, "scan", later.toString(), "--classpath-file", CLASSPATH)).doesNotContain("Sets include-stacktrace");

        // a value set only for one profile is not the service's value
        Path profile = tmp.resolve("profile");
        copy(BEFORE, profile);
        Path yml = profile.resolve("src/main/resources/application.yml");
        Files.writeString(yml, Files.readString(yml) + "---\nspring:\n  config:\n    activate:\n      on-profile: dev\n"
                + "server:\n  error:\n    include-stacktrace: always\n");
        assertThat(run(profile, "scan", profile.toString(), "--classpath-file", CLASSPATH)).doesNotContain("ERROR_DETAILS_EXPOSED");

        // a library module's application.yml (sorted first) is not the service's configuration, and no fix goes there
        Path modules = tmp.resolve("modules");
        copy(BEFORE, modules);
        Path lib = modules.resolve("aaa-lib/src/main/resources/application.yml");
        Files.createDirectories(lib.getParent());
        Files.writeString(lib, "server:\n  error:\n    include-stacktrace: always\n");
        String libScan = run(modules, "scan", modules.toString(), "--classpath-file", CLASSPATH);
        assertThat(libScan).doesNotContain("ERROR_DETAILS_EXPOSED").doesNotContain("aaa-lib/src/main/resources/application.yml");
    }

    /**
     * A key defined in a later unconditional document of application.yml (#47): Spring applies the documents top to
     * bottom, so the fix must change it there; written into the first document it has no effect and the finding stays.
     * The profile document after it keeps its own value. Every file must equal e2e/expected-config-later-document.
     */
    @Test
    void configFixLandsInTheDocumentThatDefinesTheKey() throws IOException {
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, app -> {
            Path yml = app.resolve("src/main/resources/application.yml");
            Files.writeString(yml, Files.readString(yml) + "---\nserver:\n  error:\n    include-stacktrace: always\n"
                    + "---\nspring:\n  config:\n    activate:\n      on-profile: dev\nserver:\n  error:\n    include-stacktrace: always\n");
        });
        String yml = actual.get("src/main/resources/application.yml");
        String[] docs = yml.split("(?m)^---\\s*$");
        assertThat(docs).hasSize(3);
        assertThat(docs[0]).as("not in the first document, where a later one would override it").doesNotContain("include-stacktrace");
        assertThat(docs[1]).as("changed in the document Spring uses").contains("include-stacktrace: never").doesNotContain("always");
        assertThat(docs[2]).as("the profile document keeps its value").contains("include-stacktrace: always");
        assertThat(actual.get("FINDINGS.txt")).doesNotContain("Sets include-stacktrace");
        golden(EXPECTED.resolveSibling("expected-config-later-document"), actual);
    }

    /**
     * Clients spring.http.clients.* does not reach (#48): Spring Boot applies the property only to the builders it
     * injects, not to {@code RestClient.create()} or {@code new RestTemplate()}. The property is set for the injected
     * builder of the fixture, and the hand-made clients stay reported where they are built. Every file must equal
     * e2e/expected-unconfigured-http-clients.
     */
    @Test
    void httpClientsTheTimeoutPropertyDoesNotReachStayReported() throws IOException {
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, app -> {
            Path source = app.resolve("src/main/java/com/example/e2e/review/PartnerLookup.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, """
                    package com.example.e2e.review;

                    import org.springframework.web.client.RestClient;
                    import org.springframework.web.client.RestTemplate;

                    class PartnerLookup {
                        String name(String partnerId) {
                            RestClient client = RestClient.create("http://partners");
                            return client.get().uri("/partners/{partnerId}", partnerId).retrieve().body(String.class);
                        }

                        String status(String partnerId) {
                            RestTemplate template = new RestTemplate();
                            return template.getForObject("http://partners/status/{partnerId}", String.class, partnerId);
                        }
                    }
                    """);
        });
        assertThat(actual.get("src/main/resources/application.yml")).as("the injected builder gets the property")
                .contains("read-timeout:");
        assertThat(actual.get("FINDINGS.txt")).as("the hand-made clients stay reported after the property is set")
                .contains("built from RestClient.create()").contains("built from new RestTemplate()");
        golden(EXPECTED.resolveSibling("expected-unconfigured-http-clients"), actual);
    }

    /**
     * Java 22+ unnamed catch parameters (#44): {@code catch (X _)} cannot be passed on as a cause or logged, so neither
     * fix may write {@code _} into the code. Needs JDK 22+ to compile.
     */
    @Test
    void unnamedCatchParametersAreNotPassedOn() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(Runtime.version().feature() >= 22, "unnamed variables need JDK 22+");
        Path app = tmp.resolve("app");
        copy(BEFORE, app);
        Path f = app.resolve("src/main/java/com/example/e2e/web/UnnamedCatch.java");
        Files.writeString(f, """
                package com.example.e2e.web;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                public class UnnamedCatch {
                    private static final Logger log = LoggerFactory.getLogger(UnnamedCatch.class);

                    public int parse(String value) {
                        try {
                            return Integer.parseInt(value);
                        } catch (NumberFormatException _) {
                            throw new IllegalArgumentException("not a number: " + value);
                        }
                    }

                    public int parseOrZero(String value) {
                        try {
                            return Integer.parseInt(value);
                        } catch (NumberFormatException _) {
                            log.warn("Value parsing failed [value={}]", value);
                            return 0;
                        }
                    }
                }
                """);
        String out = run(app, "apply", app.toString(), "--auto", "--write", "--classpath-file", CLASSPATH);
        assertThat(out).doesNotContain("CHECK FAILED");
        assertThat(Files.readString(f)).doesNotContain(", _)").doesNotContain("(_)");
        compile(app);
    }

    /**
     * Order ids are anchored to code, not to line numbers (#43): a line added at the top of every file shifts every
     * finding, and no id may change, so baselines, fills.json and --only keep matching.
     */
    @Test
    void orderIdsSurviveShiftedLines() throws IOException {
        Path app = tmp.resolve("app");
        copy(BEFORE, app);
        java.util.function.Supplier<List<String>> ids = () -> {
            run(app, "scan", app.toString(), "--classpath-file", CLASSPATH);
            try {
                return java.util.regex.Pattern.compile("\"id\" ?: ?\"([A-Z]+-[0-9a-f]+)\"")
                        .matcher(Files.readString(app.resolve(".logast/orders.json"))).results().map(r -> r.group(1)).sorted().toList();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        List<String> before = ids.get();
        assertThat(before).as("the scan finds orders").hasSizeGreaterThan(50);
        try (var files = Files.walk(app.resolve("src"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Files.writeString(f, Files.readString(f).replaceFirst("(?m)^(package [^;]+;)$", "$1\n"));
            }
        }
        assertThat(ids.get()).as("ids after a line was added above every finding").isEqualTo(before);
    }

    @Test
    void shapeExportHidesTheWordsAndKeepsTheFindings() throws IOException {
        Path app = tmp.resolve("app");
        copy(BEFORE, app);
        run(app, "shape", app.toString(), "--classpath-file", CLASSPATH);
        Path shape = tmp.resolve("shape");
        copy(app.resolve(".logast/shape"), shape);
        // the libraries the export needs, by coordinate, Lombok included; no local paths
        String libraries = Files.readString(shape.resolve("libraries.txt"));
        assertThat(libraries).contains("org.slf4j:slf4j-api:").contains("org.projectlombok:lombok:").doesNotContain("/home/")
                .doesNotContain(System.getProperty("user.home"));
        try (var files = Files.walk(shape)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                String text = Files.readString(f).toLowerCase(java.util.Locale.ROOT) + f;
                for (String word : List.of("coupon", "agreement", "income", "funding", "sales", "redeem", "voucher")) {
                    assertThat(text).as(f + " still says " + word).doesNotContain(word);
                }
            }
        }
        compile(shape);
        java.util.function.Function<Path, List<String>> rules = root -> run(root, "scan", root.toString(), "--format", "summary",
                "--classpath-file", CLASSPATH).lines().map(String::strip).filter(l -> l.matches("[A-Z_]+ +\\d+.*"))
                .map(l -> l.replaceAll("^([A-Z_]+ +\\d+).*", "$1")).filter(l -> !l.startsWith("SETUP_SUPPORT_STARTER")).sorted().toList();
        assertThat(rules.apply(shape)).as("the export finds what the original finds").isEqualTo(rules.apply(app));
    }

    @Test
    void rewordingTheGeneratedLinesConverges() throws IOException {
        Path app = tmp.resolve("app");
        copy(BEFORE, app);
        run(app, "apply", app.toString(), "--auto", "--write", "--classpath-file", CLASSPATH);

        Path fills = app.resolve(".logast/fills.json");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(fills.toFile());
        int n = 0;
        for (var it = root.fields(); it.hasNext(); ) {
            var e = it.next();
            if (e.getKey().startsWith("_") || !(e.getValue() instanceof com.fasterxml.jackson.databind.node.ObjectNode entry)) {
                continue;
            }
            for (var f = entry.fieldNames(); f.hasNext(); ) {
                String slot = f.next();
                if (slot.startsWith("_") || slot.equals("args")) {
                    continue;
                }
                String hint = entry.path("_" + slot).asText("");
                java.util.regex.Matcher form = java.util.regex.Pattern.compile("must read as: (\\{subject} \\w+)").matcher(hint);
                entry.put(slot, form.find() ? form.group(1).replace("{subject}", "Reworded step " + (++n)) : "Reworded line " + (++n));
            }
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(fills.toFile(), root);

        String second = run(app, "apply", app.toString(), "--auto", "--write", "--classpath-file", CLASSPATH);
        String third = run(app, "apply", app.toString(), "--auto", "--write", "--classpath-file", CLASSPATH);
        assertThat(second).as("the reworded lines are applied").doesNotContain("REJECTED").doesNotContain("CHECK FAILED");
        assertThat(third).as("nothing left to change after the rewording:\n" + third).contains("applied (0)").doesNotContain("CHECK FAILED");
        compile(app);
    }

    /** A person rewrites a generated line in the code, in their own words: logast must not add another one. */
    @Test
    void freelyRewordedLifecycleLinesAreRecognisedByTheirRole() throws IOException {
        Path app = tmp.resolve("app");
        copy(BEFORE, app);
        run(app, "apply", app.toString(), "--auto", "--write", "--classpath-file", CLASSPATH);
        Path job = app.resolve("src/main/java/com/example/e2e/coupon/CouponCleanupJob.java");
        String text = Files.readString(job);
        assertThat(text).contains("\"Coupon cleanup started\"", "\"Coupon cleanup completed [couponCount={}]\"");
        Files.writeString(job, text.replace("\"Coupon cleanup started\"", "\"Nightly sweep begins\"")
                .replace("\"Coupon cleanup completed [couponCount={}]\"", "\"Nightly sweep done, closed {} coupons\""));

        run(app, "apply", app.toString(), "--auto", "--write", "--classpath-file", CLASSPATH);
        String after = Files.readString(job);
        assertThat(after).doesNotContain("Coupon cleanup started").doesNotContain("Coupon cleanup completed");
        assertThat(after.split("log\\.info\\(", -1).length - 1).as(after).isEqualTo(2);   // still one start, one end
    }

    /**
     * A service migrated by an earlier logast version has its scopes too low (calculate, calculateIncome). A new run
     * puts the scope at the top of the flow (processRow) and unwraps the old ones: they are nested now.
     */
    @Test
    void scopesLeftTooLowByAnEarlierRunMoveUp() throws IOException {
        Path app = tmp.resolve("app");
        copy(BEFORE, app);
        Path calc = app.resolve("src/main/java/com/example/e2e/income/IncomeCalculator.java");
        // the calculator as an earlier logast version left it: scopes in calculate and calculateIncome
        Files.writeString(calc, """
                package com.example.e2e.income;

                import com.example.e2e.logging.MdcScope;
                import java.math.BigDecimal;
                import java.util.List;
                import java.util.Optional;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                public final class IncomeCalculator {
                    private static final Logger log = LoggerFactory.getLogger(IncomeCalculator.class);

                    private IncomeCalculator() {
                    }

                    public static IncomeOutcome calculate(SalesTotals salesData, List<CouponDetails> matchingCoupons) {
                        try (var mdc = MdcScope.of("couponId", salesData.couponId())) {
                            log.debug("Calculating income [saleDay={}, matchingCoupons={}]", salesData.saleDay(), matchingCoupons.size());
                            if (matchingCoupons.isEmpty()) {
                                log.debug("Coupon not found in the coupon list");
                                return new IncomeOutcome(true, BigDecimal.ZERO);
                            }
                            return evaluateOutcome(salesData, matchingCoupons);
                        }
                    }

                    private static IncomeOutcome evaluateOutcome(SalesTotals salesData, List<CouponDetails> sortedCoupons) {
                        for (CouponDetails coupon : sortedCoupons) {
                            Optional<String> exclusion = validateCoupon(salesData, coupon);
                            if (exclusion.isEmpty()) {
                                return calculateIncome(salesData, coupon);
                            }
                            log.trace("Coupon excluded [couponId={}, reason={}]", coupon.couponId(), exclusion.get());
                        }
                        return new IncomeOutcome(true, BigDecimal.ZERO);
                    }

                    private static Optional<String> validateCoupon(SalesTotals salesData, CouponDetails coupon) {
                        for (CouponCheck check : CouponChecks.all()) {
                            Optional<String> result = check.validate(coupon, salesData);
                            if (result.isPresent()) {
                                return result;
                            }
                        }
                        return Optional.empty();
                    }

                    private static IncomeOutcome calculateIncome(SalesTotals salesData, CouponDetails coupon) {
                        try (var mdc = MdcScope.of("couponId", coupon.couponId())) {
                            BigDecimal result = salesData.totalDiscount().multiply(coupon.fundingPercentage());
                            log.debug("Income calculated [saleDay={}, result={}]", salesData.saleDay(), result);
                            return new IncomeOutcome(false, result);
                        }
                    }
                }
                """);

        // and a check with a two-key scope (couponId + agreementId), as earlier versions wrote them
        Path checks = app.resolve("src/main/java/com/example/e2e/income/CouponChecks.java");
        Files.writeString(checks, Files.readString(checks)
                .replace("import java.util.List;", "import com.example.e2e.logging.MdcScope;\nimport java.util.List;")
                .replace("""
                                    log.trace(CHECK_LOG_MESSAGE, "funding", coupon.couponId(), coupon.agreementId(), salesData.saleDay());
                                    if (coupon.fundingPercentage() == null) {
                                        log.debug("No funding found [couponId={}, agreementId={}]", coupon.couponId(), coupon.agreementId());
                                        return Optional.of("NO_FUNDING");
                                    }
                                    return Optional.empty();
                        """, """
                                    try (var mdc = MdcScope.of("couponId", coupon.couponId(), "agreementId", coupon.agreementId())) {
                                        log.trace(CHECK_LOG_MESSAGE, "funding", coupon.couponId(), coupon.agreementId(), salesData.saleDay());
                                        if (coupon.fundingPercentage() == null) {
                                            log.debug("No funding found");
                                            return Optional.of("NO_FUNDING");
                                        }
                                        return Optional.empty();
                                    }
                        """));
        assertThat(Files.readString(checks)).as("test setup").contains("MdcScope.of(\"couponId\", coupon.couponId(), \"agreementId\"");

        String out = run(app, "apply", app.toString(), "--auto", "--write", "--classpath-file", CLASSPATH);
        assertThat(out).doesNotContain("CHECK FAILED");
        assertThat(Files.readString(app.resolve("src/main/java/com/example/e2e/income/CalculationService.java")))
                .contains("MdcScope.of(\"couponId\", salesData.couponId())");
        assertThat(Files.readString(calc)).doesNotContain("\"couponId\"");
        assertThat(Files.readString(checks)).as(Files.readString(checks)).doesNotContain("\"couponId\"");
        compile(app);
    }

    /** Runs the CLI in-process; stdout with the temp path, timings and colours normalised. */
    private static String run(Path app, String... args) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            new CommandLine(new Main()).execute(args);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        return buf.toString(StandardCharsets.UTF_8)
                .replace(app.toString(), "<app>")
                .replaceAll("\u001b\\[[0-9;]*m", "")
                .replaceAll("\\d+(\\.\\d+)?s\\b", "<t>")
                .replaceAll("(?m)[ \t]+$", "");
    }

    private static Map<String, String> files(Path root) throws IOException {
        Map<String, String> out = new TreeMap<>();
        if (!Files.exists(root)) {
            return out;
        }
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                // the copied support classes are logast's own code: compiled, not compared
                if (rel.startsWith("out/") || rel.startsWith("build/") || rel.startsWith(".gradle/") || SKIP.contains(rel)
                        || rel.startsWith("src/main/java/com/example/e2e/logging/")) {
                    continue;
                }
                out.put(rel, Files.readString(p));
            }
        }
        return out;
    }

    /** The first differing lines of a file, enough to see what changed. */
    private static String diff(String file, String expected, String actual) {
        List<String> e = expected.lines().toList();
        List<String> a = actual.lines().toList();
        StringBuilder sb = new StringBuilder("changed: " + file + "\n");
        int shown = 0;
        for (int i = 0; i < Math.max(e.size(), a.size()) && shown < 12; i++) {
            String x = i < e.size() ? e.get(i) : "<none>";
            String y = i < a.size() ? a.get(i) : "<none>";
            if (!x.equals(y)) {
                sb.append(String.format("  line %d%n    - %s%n    + %s%n", i + 1, x, y));
                shown++;
            }
        }
        return sb.toString();
    }

    private static void compile(Path root) throws IOException {
        List<String> sources;
        try (Stream<Path> s = Files.walk(root.resolve("src/main/java"))) {
            sources = s.filter(p -> p.toString().endsWith(".java")).map(Path::toString).toList();
        }
        List<String> cp = new ArrayList<>(Files.readAllLines(Path.of(CLASSPATH)).stream().filter(l -> !l.isBlank()).toList());
        cp.add(PLATFORM.toString());
        List<String> args = new ArrayList<>(List.of("-d", root.resolve("out").toString(), "-proc:full",
                "-processorpath", LOMBOK, "-cp", String.join(java.io.File.pathSeparator, cp)));
        args.addAll(sources);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = javac.run(null, null, err, args.toArray(String[]::new));
        assertThat(rc).as("the migrated project must compile:\n" + err).isZero();
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path dst = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dst);
                } else {
                    Files.copy(p, dst);
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
