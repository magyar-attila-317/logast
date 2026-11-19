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
abstract class E2eGoldenTest {
    /** Shards run in parallel JVMs (#110): the subclasses E2eShard0..3 each run the scenarios whose name hashes to them. */
    static final int SHARDS = 4;

    @org.junit.jupiter.api.BeforeEach
    void onlyThisShardsScenarios(org.junit.jupiter.api.TestInfo info) {
        String name = getClass().getSimpleName();
        int shard = name.startsWith("E2eShard") ? Integer.parseInt(name.substring("E2eShard".length())) : 0;
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Math.floorMod(info.getTestMethod().orElseThrow().getName().hashCode(), SHARDS) == shard, "another shard runs it");
    }

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
    private static final Set<String> SKIP = Set.of(".logast/reports/orders.json", ".logast/classpath.txt", ".logast/classpath-root.txt");

    @TempDir
    Path tmp;
    /** The charset javac reads the migrated sources in ({@code options.encoding} of the service). */
    private String sourceEncoding = "UTF-8";
    /** Compiled logging-support classes javac gets on top of the classpath (dependency mode: what the starter brings). */
    private Path starterClasses;
    /** Jars javac gets on top of the e2e classpath: stand-ins for libraries one scenario needs. */
    private Path[] extraClasses = {};

    @Test
    void migrationMatchesTheGoldenSnapshot() throws IOException {
        golden(EXPECTED, migrate(tmp.resolve("app"), CLASSPATH, app -> {
        }));
    }

    /**
     * #117: the first apply adds dependencies (the OpenTelemetry starter, logging-support) whose types setup decisions
     * depend on (service identity's OTel attributes and meter tags, the support classes that need Micrometer). Before
     * the classpath logast reads has them, those decisions come out one apply late. When apply refreshed the classpath
     * itself, it runs one more round with it: a second apply changes nothing. The refresh is a stand-in gradlew that
     * writes the full classpath (no Gradle run in the test); the service starts without Micrometer and OpenTelemetry.
     */
    @Test
    void anApplyThatAddsDependenciesSettlesWithTheRefreshedClasspath() throws IOException {
        Path app = tmp.resolve("app");
        copy(BEFORE, app);
        List<String> full = Files.readAllLines(Path.of(CLASSPATH));
        Path cp = app.resolve(".logast/classpath-main.txt");
        Files.createDirectories(cp.getParent());
        Files.write(cp, full.stream().filter(l -> !l.contains("micrometer") && !l.contains("opentelemetry")).toList());
        Path fullCp = tmp.resolve("full-classpath.txt");
        Files.write(fullCp, full);
        Path gradlew = app.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\ncp '" + fullCp + "' .logast/classpath-main.txt\n");
        assertThat(gradlew.toFile().setExecutable(true)).isTrue();

        String first = run(app, "apply", app.toString(), "--auto", "--write");
        assertThat(first.split("\napplied \\(", -1).length - 1 + (first.startsWith("applied (") ? 1 : 0))
                .as("the dependencies changed and the classpath refreshed: one more round, with what the new types allow\n" + first)
                .isEqualTo(2);
        assertThat(STDERR.toString()).contains("one more round with the refreshed classpath");
        String second = run(app, "apply", app.toString(), "--auto", "--write");
        assertThat(second).as("settled in that one apply").contains("applied (0):");
    }

    /**
     * #112: the whole e2e service with logging.keyStyle: dotted keeps every invariant (one pass, a second apply changes
     * nothing, no CHECK FAILED, compiles): a value MDC carries under run.code leaves a [runCode={}] / [run.code={}]
     * suffix like it does under camel (the key names the Java value runCode). Every file must equal
     * e2e/expected-key-style-dotted.
     */
    @Test
    void dottedKeyStyleKeepsTheInvariants() throws IOException {
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, app -> {
            Path yml = app.resolve("logast.yml");
            Files.writeString(yml, Files.readString(yml).replace("keyStyle: camel", "keyStyle: dotted"));
        });
        assertThat(actual.get("src/main/java/com/example/e2e/job/BaseRunJob.java")).contains("MdcScope.of(\"run.code\", runCode)");
        assertThat(actual.get("src/main/java/com/example/e2e/job/PublishRunJob.java")).as("MDC carries run.code: not in the messages")
                .contains("log.info(\"Publishing started\");").doesNotContain("run.code={}");
        golden(EXPECTED.resolveSibling("expected-key-style-dotted"), actual);
    }

    /**
     * #18: one line logs a plain [order=…], another the property order.id: the search index maps order as an object
     * and rejects the first line (Elasticsearch expands dotted names; Boot's ECS format nests them). Reported, with
     * both places. (keyStyle: keep: the keys as written; dotted is #112.) Every file must equal
     * e2e/expected-key-nesting-conflict.
     */
    @Test
    void aFieldNextToItsDottedSubFieldIsReported() throws IOException {
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, app -> {
            Path yml = app.resolve("logast.yml");
            Files.writeString(yml, Files.readString(yml).replace("keyStyle: camel", "keyStyle: keep"));
            Path dir = Files.createDirectories(app.resolve("src/main/java/com/example/e2e/shipment"));
            Files.writeString(dir.resolve("ShipmentLog.java"), """
                    package com.example.e2e.shipment;

                    import org.slf4j.Logger;
                    import org.slf4j.LoggerFactory;
                    import org.springframework.stereotype.Component;

                    @Component
                    public class ShipmentLog {
                        private static final Logger log = LoggerFactory.getLogger(ShipmentLog.class);

                        public void shipped(String order, String shipmentId) {
                            log.info("Shipment sent [order={}]", order);
                            log.atInfo().addKeyValue("order.id", shipmentId).log("Shipment confirmed");
                        }
                    }
                    """);
        });
        assertThat(actual.get("FINDINGS.txt")).as("the plain order next to the property order.id")
                .contains("REPORT-ONLY  LOG_KEY_NESTING_CONFLICT  src/main/java/com/example/e2e/shipment/ShipmentLog.java:12")
                .contains("also fixes: LOG_KEY_NESTING_CONFLICT src/main/java/com/example/e2e/shipment/ShipmentLog.java:13");
        golden(EXPECTED.resolveSibling("expected-key-nesting-conflict"), actual);
    }

    /**
     * #21: BUSINESS_EVENT_UNMEASURED reads the wording a team really uses: the phrasing variants ("Starting X" …
     * "Finished X") and "Processing X" with X's completion in the same method ("Ledger import done"). "Processing X"
     * alone (a per-item line) is no business event, and it is not a phrasing variant: the line keeps its words. Every
     * file must equal e2e/expected-business-event-wording.
     */
    @Test
    void businessEventsInTheTeamsOwnWording() throws IOException {
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, app -> {
            Path dir = Files.createDirectories(app.resolve("src/main/java/com/example/e2e/ledger"));
            Files.writeString(dir.resolve("LedgerJobs.java"), """
                    package com.example.e2e.ledger;

                    import org.slf4j.Logger;
                    import org.slf4j.LoggerFactory;
                    import org.springframework.stereotype.Component;

                    @Component
                    public class LedgerJobs {
                        private static final Logger log = LoggerFactory.getLogger(LedgerJobs.class);

                        public void syncRefunds() {
                            log.info("Starting refund sync");
                            work();
                            log.info("Finished refund sync");
                        }

                        public void importLedger() {
                            log.info("Processing ledger import");
                            work();
                            log.info("Ledger import done");
                        }

                        public void touchOrders() {
                            log.info("Processing order batch");
                            work();
                        }

                        private void work() {
                        }
                    }
                    """);
        });
        String jobs = actual.get("src/main/java/com/example/e2e/ledger/LedgerJobs.java");
        assertThat(jobs).as("both wordings are business events: each is counted")
                .contains("ObservedContext.entrypoint(LedgerMetrics.REFUND_SYNC)")
                .contains("ObservedContext.entrypoint(LedgerMetrics.LEDGER_IMPORT)")
                .as("\"Processing X\" alone is no business event, and keeps its words").doesNotContain("ORDER_BATCH")
                .contains("\"Processing order batch\"");
        golden(EXPECTED.resolveSibling("expected-business-event-wording"), actual);
    }

    /**
     * #106: the Splunk fields follow the format the service really writes. Here nothing configures Spring's structured
     * logging and logast.yaml keeps the default (ecs), but logback-spring.xml uses the Logstash encoder: the queries
     * use its fields ({@code level}, {@code logger_name}), not ECS's ({@code log.level}).
     */
    @Test
    void queriesUseTheFieldsOfTheEncoderTheServiceUses() throws IOException {
        Path app = tmp.resolve("app");
        copy(BEFORE, app);
        Path yml = app.resolve("logast.yml");
        Files.writeString(yml, Files.readString(yml).replace("  structuredFormat: logstash\n", "")
                + "queries:\n  splunk:\n    index: shop-logs\n");
        Path props = app.resolve("src/main/resources/application.yml");
        Files.writeString(props, Files.readString(props).replace("logging:\n  structured:\n    format:\n      console: ecs\n", ""));
        Files.writeString(app.resolve("src/main/resources/logback-spring.xml"), """
                <configuration>
                  <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
                    <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
                  </appender>
                  <root level="INFO"><appender-ref ref="JSON"/></root>
                </configuration>
                """);
        String q = run(app, "queries", app.toString(), "--stdout", "--classpath-file", CLASSPATH);
        assertThat(q).as("Logstash fields").contains("in the `logstash` format")
                .contains("index=shop-logs \"service.name\"=\"e2e-shop\" level=ERROR\n| stats count latest(_time) as last by logger_name, message")
                .doesNotContain("log.level");

        // only logast.yaml says logstash, Spring's property still says ecs (no apply yet): logast.yaml wins, as apply makes it so
        Path policyOnly = tmp.resolve("policy-only");
        copy(BEFORE, policyOnly);
        Files.writeString(policyOnly.resolve("logast.yml"), Files.readString(policyOnly.resolve("logast.yml"))
                + "queries:\n  splunk:\n    index: shop-logs\n");
        assertThat(Files.readString(policyOnly.resolve("src/main/resources/application.yml"))).contains("console: ecs");
        assertThat(run(policyOnly, "queries", policyOnly.toString(), "--stdout", "--classpath-file", CLASSPATH))
                .as("the format of logast.yaml").contains("in the `logstash` format").contains(" level=ERROR").doesNotContain("log.level");
    }

    /**
     * #107: scan for a person at a terminal: counts, then the decisions grouped by severity and rule, what to do per
     * rule (the first instruction of the notes), one line per finding below the application package (two findings on one line and text as one, ×2), at most four per rule.
     * Piped, scan keeps the full listing every other scenario snapshots (FINDINGS.txt). Must equal
     * e2e/expected-scan-human/SCAN.txt.
     */
    @Test
    void scanForAPersonGroupsTheDecisions() throws IOException {
        Path app = tmp.resolve("app");
        copy(BEFORE, app);
        String human = run(app, "scan", app.toString(), "--format", "human", "--classpath-file", CLASSPATH);
        assertThat(human).as("grouped, below the application package, the same place twice as one line")
                .contains("Your decisions").contains("    async work loses the log context (4)  ASYNC_CONTEXT_LOSS\n")
                .contains("      web/QuoteController.java:18  CompletableFuture.join() without a timeout")
                .contains(" ×2\n").contains("      +2 more\n").doesNotContain("src/main/java/com/example/e2e/")
                .contains("outcomes to count or skip").contains("--rule ASYNC_CONTEXT_LOSS")
                .as("each rule says what to do: the note's first instruction, past an explanation")
                .contains("      → Pass a context-propagating executor: ContextExecutors.wrap(executor) or a Spring TaskExecutor bean.\n")
                .contains("      → Log it there (or in an @ExceptionHandler), then delete this one.\n")
                .contains("      → e.g. Record the trace or correlation id");
        String piped = run(app, "scan", app.toString(), "--classpath-file", CLASSPATH);
        assertThat(piped).as("not a terminal: the full listing").contains("REPORT-ONLY").doesNotContain("Your decisions");
        golden(EXPECTED.resolveSibling("expected-scan-human"), Map.of("SCAN.txt", human));
    }

    /**
     * Scenarios that snapshot logast's own output in full (#110): the reports, the decision files, OUTPUT.txt and the
     * finding texts. Every other scenario snapshots what logast changes in the service, and its findings as a list.
     */
    private static final Set<String> FULL_REPORTS = Set.of("expected", "expected-decided", "expected-decided-off",
            "expected-decided-single", "expected-scan-human", "expected-init", "expected-log-call-coverage");
    /** Report or decision files that are the subject of a scenario that otherwise snapshots only the service. */
    private static final Map<String, Set<String>> KEEP = Map.of(
            "expected-retry-settings", Set.of(".logast/decisions/retry.yaml"),
            "expected-retry-coverage", Set.of(".logast/decisions/retry.yaml"),
            "expected-retry-decided", Set.of(".logast/decisions/retry.yaml"),
            "expected-amounts-off", Set.of(".logast/decisions/metrics.yaml"));
    private static final java.util.regex.Pattern FINDING = java.util.regex.Pattern.compile(
            "(?m)^[A-Z]{3}-[0-9a-f]{6}\\s+\\S+\\s+(AUTO|NEEDS TEXT|REPORT-ONLY)\\s+(\\S+)\\s+(\\S+:\\d+)");

    /**
     * What a scenario's snapshot holds (#110): everything for {@link #FULL_REPORTS}; else the service's files, the
     * findings as "KIND RULE file:line" (which findings remain and where, not their wording), RULES.txt (the rules
     * applied, brought up by a rescan, also fixed: what RuleLedgerTest counts as firing) and the {@link #KEEP} files.
     * A change of report wording, ids or the .logast layout then touches only the full scenarios.
     */
    static Map<String, String> scoped(String scenario, Map<String, String> files) {
        if (FULL_REPORTS.contains(scenario)) {
            return files;
        }
        Set<String> keep = KEEP.getOrDefault(scenario, Set.of());
        Map<String, String> out = new TreeMap<>();
        for (var e : files.entrySet()) {
            String k = e.getKey();
            if (keep.contains(k)) {
                out.put(k, e.getValue());
            } else if (k.equals("FINDINGS.txt")) {
                StringBuilder sb = new StringBuilder();
                java.util.regex.Matcher m = FINDING.matcher(e.getValue());
                while (m.find()) {
                    sb.append(m.group(1)).append(' ').append(m.group(2)).append(' ').append(m.group(3)).append('\n');
                }
                out.put(k, sb.toString());
            } else if (!k.startsWith(".logast/") && !k.equals("OUTPUT.txt")) {
                out.put(k, e.getValue());
            }
        }
        // which rules the migration applied, which a rescan brought up, which a finding also fixes: behaviour, not wording
        SortedSet<String> rules = new TreeSet<>();
        String trace = files.getOrDefault("OUTPUT.txt", "") + "\n" + files.getOrDefault(".logast/reports/apply-trace.txt", "");
        for (String line : trace.lines().map(String::strip).toList()) {
            if (line.startsWith("applied (")) {   // "applied (3): BET-466d72 FMT-0447e4 …": the rule is the id's prefix
                for (java.util.regex.Matcher m = ORDER_ID.matcher(line); m.find(); ) {
                    String rule = dev.logast.rules.OrderBuilder.ruleOfPrefix(m.group(1));
                    if (rule != null) {
                        rules.add("applied " + rule);
                    }
                }
            }
            String what = line.contains("applying [") ? "applied " : line.contains("rescan: new [") ? "rescan " : null;
            for (java.util.regex.Matcher m = TRACED.matcher(line); what != null && m.find(); ) {
                rules.add(what + m.group(1));
            }
        }
        for (java.util.regex.Matcher m = ALSO_FIXES.matcher(files.getOrDefault("FINDINGS.txt", "")); m.find(); ) {
            rules.add("also " + m.group(1));
        }
        if (!rules.isEmpty()) {
            out.put("RULES.txt", String.join("\n", rules) + "\n");
        }
        return out;
    }

    private static final java.util.regex.Pattern ORDER_ID = java.util.regex.Pattern.compile("\\b([A-Z]{3})-[0-9a-f]{6}\\b");
    private static final java.util.regex.Pattern TRACED = java.util.regex.Pattern.compile("([A-Z][A-Z_]+) [A-Z]{3}-[0-9a-f]{6}");
    private static final java.util.regex.Pattern ALSO_FIXES = java.util.regex.Pattern.compile("(?m)^\\s+also fixes: ([A-Z_]+)\\s");

    /** Every file equals the snapshot in {@code expectedDir}; with -Pe2e.update the snapshot is rewritten instead. */
    private static void golden(Path expectedDir, Map<String, String> all) throws IOException {
        Map<String, String> actual = scoped(expectedDir.getFileName().toString(), all);
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
        // what went wrong on a real service: the checks, reached only through PledgeCheck.validate, lost their caller
        // in later rounds and got scopes of their own although the calculator's scope already covers them
        assertThat(actual.get("src/main/java/com/example/e2e/stipend/PledgeChecks.java"))
                .as("checks called through the interface are covered by the calculator's scope").doesNotContain("MdcScope");
        assertSameAsMain(actual);
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
     * The whole process: migrate, then write every message text logast asks for (messages.yaml) and count every outcome
     * (metrics.yaml), then apply once more. Every decision must land, also the ones on lines the texts reword (their
     * ids used to include the message text, so a reworded line lost its decision without a word). Then {@code logast
     * queries} writes .logast/reports/queries.md for what the service now measures (#106). Every file must equal
     * e2e/expected-decided.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "metrics.catalog: {0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"per-package", "single", "off"})
    void filledTextsAndMetricDecisionsAllLand(String catalog) throws IOException {
        decided(catalog, app -> {
        });
    }

    /**
     * #108: a service set up by an earlier logast keeps its decisions in the old layout: .logast/fills.json and
     * .logast/metrics.json (JSON, with a "_readme"), traffic.yaml and the reports at the top of .logast. The next apply
     * moves them into decisions/ (as YAML) and reports/, says so in one line, and honours every decision: the result
     * equals the run in the new layout, every file must equal e2e/expected-decided.
     */
    @Test
    void decisionsInTheEarlierLayoutAreMovedAndHonoured() throws IOException {
        STDERR.setLength(0);
        decided("per-package", E2eGoldenTest::toEarlierLayout);
        assertThat(STDERR.toString()).as("one line says what moved").contains("moved to the new .logast layout: "
                + "fills.json → decisions/messages.yaml, metrics.json → decisions/metrics.yaml, traffic.yaml → decisions/traffic.yaml, "
                + "orders.json, coverage.txt, apply-trace.txt, last-apply.txt, steps → reports/\n");
        for (String old : List.of("fills.json", "metrics.json", "traffic.yaml", "orders.json", "coverage.txt", "steps")) {
            assertThat(tmp.resolve("app/.logast").resolve(old)).as("moved: " + old).doesNotExist();
        }
    }

    /** The .logast of an earlier logast: fills.json and metrics.json as JSON with their "_readme", every other file at the top. */
    private static void toEarlierLayout(Path app) throws IOException {
        Path dir = app.resolve(".logast");
        Map<String, String> json = Map.of("decisions/messages.yaml", "fills.json", "decisions/metrics.yaml", "metrics.json");
        for (var e : json.entrySet()) {
            var tree = (com.fasterxml.jackson.databind.node.ObjectNode) dev.logast.apply.DecisionYaml.MAPPER.readTree(dir.resolve(e.getKey()).toFile());
            var old = dev.logast.orders.OrderJson.MAPPER.createObjectNode().put("_readme", "Write the message text for each order ...");
            old.setAll(tree);
            dev.logast.orders.OrderJson.MAPPER.writerWithDefaultPrettyPrinter().writeValue(dir.resolve(e.getValue()).toFile(), old);
            Files.delete(dir.resolve(e.getKey()));
        }
        for (String sub : List.of("decisions", "reports")) {
            try (Stream<Path> s = Files.list(dir.resolve(sub))) {
                for (Path p : s.toList()) {
                    Files.move(p, dir.resolve(p.getFileName()));
                }
            }
            Files.delete(dir.resolve(sub));
        }
        assertThat(dir.resolve("traffic.yaml")).exists();
        assertThat(dir.resolve("steps")).isDirectory();
    }

    /**
     * migrate, write every text, count every outcome, {@code beforeApply}, apply, compile, logast queries: every file
     * must equal the expected-decided snapshot of the catalog.
     */
    private void decided(String catalog, IoAction beforeApply) throws IOException {
        Path app = tmp.resolve("app");
        migrate(app, CLASSPATH, a -> {
            // the manual flow: nothing counted by default, every outcome decided in metrics.yaml
            Path yml = a.resolve("logast.yml");
            Files.writeString(yml, Files.readString(yml) + "metrics:\n  defaults: []\n"
                    + (catalog.equals("per-package") ? "" : "  catalog: " + catalog + "\n")   // per-package is the default
                    + "queries:\n  splunk:\n    index: shop-logs\n");   // logast queries (#106), run at the end
        });
        writeTexts(app);
        Path metrics = app.resolve(".logast/decisions/metrics.yaml");
        assertThat(Files.readString(metrics)).as("metrics.yaml has outcomes to decide").contains("count: \"\"");
        // count every outcome; where the line logs a number, count that too: the first entry per run, the rest totals
        int[] amounts = {0};
        boolean[] tagged = {false};
        long decided = editDecisions(metrics, root -> {
            long n = 0;
            for (var entry : entries(root)) {
                if (entry.path("count").asText("x").isEmpty()) {
                    // #25: one outcome tags the timer already running instead of counting: Outcomes.tag(..)
                    boolean tag = !tagged[0] && entry.path("_tag").asText("").equals("available");
                    entry.put("count", tag ? "tag" : "counter");
                    tagged[0] |= tag;
                    n++;
                    if (tag) {
                        continue;
                    }
                }
                if (entry.path("amount").asText("x").isEmpty() && entry.path("as").asText("x").isEmpty()
                        && entry.path("_amounts").path(0).asText("").matches("\\w+")) {
                    entry.put("amount", entry.path("_amounts").path(0).asText());
                    entry.put("as", amounts[0]++ == 0 ? "per-run" : "");
                }
            }
            return n;
        });
        assertThat(amounts[0]).as("some outcome lines log a number to count").isPositive();
        assertThat(tagged[0]).as("one outcome is a tag on a running timer").isTrue();
        int amountsDecided = amounts[0];
        beforeApply.run(app);

        String third = run(app, "apply", app.toString(), "--auto", "--write", "--classpath-file", CLASSPATH);
        Files.writeString(app.resolve("OUTPUT.txt"), Files.readString(app.resolve("OUTPUT.txt"))
                + "\n=== after writing every text and counting every outcome\n" + third);
        compile(app);
        // #106: the queries for what the service now measures and logs; every counter, observation and MDC key shows
        String queries = run(app, "queries", app.toString(), "--classpath-file", CLASSPATH);
        assertThat(queries).as("written, all three backends").contains("PromQL").contains("NRQL").contains("SPL");
        assertThat(Files.readString(app.resolve(".logast/reports/queries.md"))).as("counters through the catalog or inline, observations "
                        + "named by catalog constants")
                .contains("sum by (outcome) (increase(pledge_resend_total{application=\"e2e-shop\"}[1h]))")
                .contains("FROM Metric SELECT count(`pledge-messages.on-batch`)")
                .contains("index=shop-logs \"service.name\"=\"e2e-shop\" orderId=\"<orderId>\"");
        Map<String, String> actual = files(app);
        // every decision is a counting line: inline (catalog off) or one call into a catalog, whose member holds the name
        long counted = actual.entrySet().stream().filter(e -> e.getKey().startsWith("src/")).map(Map.Entry::getValue)
                .mapToLong(t -> t.lines().filter(l -> l.contains("Outcomes.count(\"") || l.contains("Outcomes.record(\"")
                        || l.contains("Outcomes.tag(\"")).count()).sum();
        long withAmount = actual.entrySet().stream().filter(e -> e.getKey().startsWith("src/")).map(Map.Entry::getValue)
                .mapToLong(t -> t.lines().filter(l -> l.matches(".*Outcomes\\.(count|record)\\(\"[^\"]+\", \"[^\"]+\", \"[^\"]+\", [^\"]+\\);.*")).count()).sum();
        // decisions that cannot be written stay in metrics.yaml with a "_problem" (e.g. a log line inside a returned pipeline)
        long problems = actual.get(".logast/decisions/metrics.yaml").lines().filter(l -> l.strip().startsWith("_problem:")).count();
        // the reactive pipeline's line (inside a return), the async failure line (inside whenComplete's if) and the line in
        // runAsync's lambda (another thread, later) cannot be counted after their statement
        assertThat(problems).as("lines that cannot be counted after their statement").isEqualTo(3);
        decided -= problems;
        if (catalog.equals("off")) {
            assertThat(counted).as("every outcome decided in metrics.yaml is counted").isEqualTo(decided);
            assertThat(withAmount).as("every amount named in metrics.yaml is counted").isEqualTo(amountsDecided);
        } else {
            List<String> catalogs = actual.entrySet().stream().filter(e -> e.getValue().contains("// logast:metrics-catalog"))
                    .map(Map.Entry::getKey).toList();
            assertThat(catalogs).as("catalog classes").isNotEmpty();
            if (catalog.equals("single")) {
                assertThat(catalogs).containsExactly("src/main/java/com/example/e2e/metrics/BusinessMetrics.java");
            }
            long outside = actual.entrySet().stream().filter(e -> e.getKey().startsWith("src/") && !catalogs.contains(e.getKey())
                            && !e.getKey().contains("/logging/"))
                    .mapToLong(e -> e.getValue().lines().filter(l -> l.matches(".*(Outcomes\\.(count|record|tag)|ObservedContext\\.entrypoint)\\(\".*")).count())
                    .sum();
            assertThat(outside).as("no metric or observation name, nor an outcome tag (#25), is left at a call site").isZero();
            // #118: each catalog registers its counters at startup (a series at 0 before the first event)
            for (String cat : catalogs) {
                String text = actual.get(cat);
                if (text.contains("Outcomes.count(") || text.contains("Outcomes.record(")) {
                    assertThat(text).as(cat + " registers its counters").contains("static final class Registration implements "
                            + "org.springframework.beans.factory.SmartInitializingSingleton").contains("Outcomes.register");
                }
            }
            long calls = actual.entrySet().stream().filter(e -> e.getKey().startsWith("src/") && !catalogs.contains(e.getKey()))
                    .mapToLong(e -> e.getValue().lines().filter(l -> l.matches("\\s*\\w*Metrics\\.[a-z]\\w*\\(.*\\);.*")).count()).sum();
            assertThat(calls).as("every outcome decided in metrics.yaml is counted through the catalog").isEqualTo(decided);
        }
        golden(EXPECTED.resolveSibling(catalog.equals("per-package") ? "expected-decided" : "expected-decided-" + catalog), actual);
    }

    /**
     * metrics.amounts: off in logast.yaml: metrics.yaml offers no amounts, and the default database counts carry no row
     * amount. Every file must equal e2e/expected-amounts-off.
     */
    @Test
    void amountsOffOffersNoAmountsAndChangesNothingElse() throws IOException {
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, app -> {
            Path yml = app.resolve("logast.yml");
            Files.writeString(yml, Files.readString(yml) + "metrics:\n  amounts: off\n");
        });
        assertThat(actual.get(".logast/decisions/metrics.yaml")).as("no amounts offered").doesNotContain("_amounts:");
        assertThat(actual.values().stream().filter(t -> t.contains("Outcomes.count(\"") && t.contains("// logast:metrics-catalog")).toList())
                .as("the default database count has no row amount").noneMatch(t -> t.contains("\"rows\""));
        golden(EXPECTED.resolveSibling("expected-amounts-off"), actual);
    }

    /**
     * #75: MANUAL_TRACE_MDC and MDC_KEY_ALIAS switched off in logast.yml. The MDC_NOT_CLOSEABLE rewrite of the same puts
     * keeps the trace key and the key's spelling (it used to drop and rename them anyway); a try/finally whose trace key
     * would have to go stays a report. Every file must equal e2e/expected-mdc-rules-off.
     */
    @Test
    void mdcRewriteLeavesSwitchedOffRulesAlone() throws IOException {
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, app -> {
            Path yml = app.resolve("logast.yml");
            Files.writeString(yml, Files.readString(yml)
                    + "rules:\n  MANUAL_TRACE_MDC: { enabled: false }\n  MDC_KEY_ALIAS: { enabled: false }\n");
            Path source = app.resolve("src/main/java/com/example/e2e/parcel/ParcelTracker.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, """
                    package com.example.e2e.parcel;

                    import org.slf4j.Logger;
                    import org.slf4j.LoggerFactory;
                    import org.slf4j.MDC;

                    class ParcelTracker {
                        private static final Logger log = LoggerFactory.getLogger(ParcelTracker.class);

                        void track(String parcelCode, String traceId) {
                            MDC.put("parcel_code", parcelCode);
                            MDC.put("traceId", traceId);
                            log.info("Parcel tracking started");
                            MDC.remove("traceId");
                            MDC.remove("parcel_code");
                        }

                        void weigh(String parcelCode) {
                            try {
                                MDC.put("parcel_code", parcelCode);
                                log.info("Parcel weighing started");
                            } finally {
                                MDC.remove("parcel_code");
                            }
                        }

                        void sort(String parcelCode, String traceId) {
                            try {
                                MDC.put("traceId", traceId);
                                MDC.put("parcel_code", parcelCode);
                                log.info("Parcel sorting started");
                            } finally {
                                MDC.clear();
                            }
                        }
                    }
                    """);
        });

        String tracker = actual.get("src/main/java/com/example/e2e/parcel/ParcelTracker.java");
        assertThat(tracker).as("the alias is not renamed").doesNotContain("\"parcelCode\"")
                .as("the trace key is not dropped").contains("MDC.put(\"traceId\", traceId);\n")
                .as("the plain puts are still scoped").contains("MdcScope.of(\"parcel_code\", parcelCode)");
        golden(EXPECTED.resolveSibling("expected-mdc-rules-off"), actual);
    }

    /**
     * #11: {@code try (var mdc = MDC.putCloseable(k, v))} removes the key on close, so a nested scope of the same key
     * wipes the outer value. It becomes {@code MdcScope.of(k, v)} (restores), with the alias renamed and a key the
     * caller already scopes unwrapped (MDC_NESTED_KEY); a putCloseable outside try-with-resources stays a report.
     * Every file must equal e2e/expected-mdc-put-closeable.
     */
    @Test
    void mdcPutCloseableBecomesMdcScope() throws IOException {
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, app -> {
            Path yml = app.resolve("logast.yml");
            Files.writeString(yml, Files.readString(yml).replace("vocabulary:\n", """
                    vocabulary:
                      shelf:
                        mdc:
                          key: shelfCode
                          aliases: [shelfRef]
                          sources:
                            params: [shelfCode]
                    """));
            Path source = app.resolve("src/main/java/com/example/e2e/shelf/ShelfScanJob.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, """
                    package com.example.e2e.shelf;

                    import org.slf4j.Logger;
                    import org.slf4j.LoggerFactory;
                    import org.slf4j.MDC;
                    import org.springframework.scheduling.annotation.Scheduled;
                    import org.springframework.stereotype.Component;

                    @Component
                    public class ShelfScanJob {
                        private static final Logger log = LoggerFactory.getLogger(ShelfScanJob.class);

                        @Scheduled(fixedDelay = 60000)
                        public void scan() {
                            String shelfCode = nextShelf();
                            try (var mdc = MDC.putCloseable("shelfCode", shelfCode)) {
                                count(shelfCode);
                            }
                        }

                        void count(String shelfCode) {
                            try (MDC.MDCCloseable again = MDC.putCloseable("shelfCode", shelfCode)) {
                                log.info("Shelf count started");
                            }
                        }

                        void step(String scanStep) {
                            try (var mdc = MDC.putCloseable("scanStep", scanStep)) {
                                log.info("Scan step started");
                            }
                        }

                        void phases() {
                            try (MDC.MDCCloseable outer = MDC.putCloseable("scanPhase", "outer")) {
                                try (MDC.MDCCloseable inner = MDC.putCloseable("scanPhase", "inner")) {
                                    log.info("Inner phase started");
                                }
                                log.info("Outer phase completed");
                            }
                        }

                        void label(String shelfRef) {
                            try (MDC.MDCCloseable c = MDC.putCloseable("shelfRef", shelfRef)) {
                                log.info("Shelf labelling started");
                            }
                        }

                        void loose(String shelfRef) {
                            MDC.putCloseable("shelfRef", shelfRef);
                            log.info("Loose scan started");
                        }

                        private String nextShelf() {
                            return "S1";
                        }
                    }
                    """);
        });

        String job = actual.get("src/main/java/com/example/e2e/shelf/ShelfScanJob.java");
        assertThat(job).as("scoped putCloseable restores instead of removing")
                .contains("MdcScope.of(\"scanStep\", scanStep)")
                .contains("MdcScope.of(\"scanPhase\", \"outer\")").contains("MdcScope.of(\"scanPhase\", \"inner\")")
                .as("the alias is renamed").contains("MdcScope.of(\"shelfCode\", shelfRef)")
                .as("only the bare putCloseable is left, its alias renamed").containsOnlyOnce("MDC.putCloseable(")
                .contains("MDC.putCloseable(\"shelfCode\", shelfRef);");
        golden(EXPECTED.resolveSibling("expected-mdc-put-closeable"), actual);
    }

    /**
     * MDC placement settles in pass 1. #78: a callee re-puts a key its caller already scopes, with a value derived from
     * it (not the source parameter), next to a remove, a finally remove, inside a try/finally, and under a caller scope
     * MISSING_DOMAIN_MDC adds in the same run: MDC_NOT_CLOSEABLE rewrites it in pass 1. #79: MDC_NESTED_KEY keeps such
     * a scope (put or putCloseable: the derived value is not the caller's) and only reports it; a callee that re-puts the
     * unchanged source parameter (recount) is still unwrapped. #12: the started line ENTRYPOINT_SILENT adds goes inside the MDC
     * scope the method opens with (a put/remove, a putCloseable, a domain scope), so it carries the key; a scope that
     * starts later in the method stays below it. Every file must equal e2e/expected-mdc-placement.
     */
    @Test
    void mdcPlacementSettlesInOnePass() throws IOException {
        Path app = tmp.resolve("app");
        migrate(app, CLASSPATH, a -> {
            Path yml = a.resolve("logast.yml");
            Files.writeString(yml, Files.readString(yml).replace("vocabulary:\n", """
                    vocabulary:
                      locker:
                        mdc:
                          key: lockerCode
                          sources:
                            params: [lockerCode]
                    """));
            Path dir = a.resolve("src/main/java/com/example/e2e/locker");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("LockerSweepJob.java"), """
                    package com.example.e2e.locker;

                    import org.slf4j.Logger;
                    import org.slf4j.LoggerFactory;
                    import org.slf4j.MDC;
                    import org.springframework.scheduling.annotation.Scheduled;
                    import org.springframework.stereotype.Component;

                    @Component
                    public class LockerSweepJob {
                        private static final Logger log = LoggerFactory.getLogger(LockerSweepJob.class);

                        @Scheduled(fixedDelay = 60000)
                        public void sweep() {
                            process(nextLocker());
                        }

                        void process(String lockerCode) {
                            try (var mdc = MDC.putCloseable("lockerCode", lockerCode)) {
                                rotate(lockerCode);
                                relabel(lockerCode);
                                inspect(lockerCode);
                                recount(lockerCode);
                                stamp(lockerCode);
                            }
                        }

                        void recount(String lockerCode) {
                            MDC.put("lockerCode", lockerCode);
                            try {
                                log.info("Locker recounted");
                            } finally {
                                MDC.remove("lockerCode");
                            }
                        }

                        void stamp(String lockerCode) {
                            try (var mdc = MDC.putCloseable("lockerCode", lockerCode.toLowerCase())) {
                                log.info("Locker stamped");
                            }
                        }

                        void rotate(String lockerCode) {
                            String slot = lockerCode.toUpperCase();
                            MDC.put("lockerCode", slot);
                            try {
                                log.info("Locker slot rotated");
                            } finally {
                                MDC.remove("lockerCode");
                            }
                        }

                        void relabel(String lockerCode) {
                            MDC.put("lockerCode", lockerCode.strip());
                            log.info("Locker relabelled");
                            MDC.remove("lockerCode");
                        }

                        void inspect(String lockerCode) {
                            try {
                                MDC.put("lockerCode", "L-" + lockerCode);
                                log.info("Locker inspected");
                            } finally {
                                MDC.remove("lockerCode");
                            }
                        }

                        private String nextLocker() {
                            return "L1";
                        }
                    }
                    """);
            Files.writeString(dir.resolve("LockerStore.java"), """
                    package com.example.e2e.locker;

                    import java.util.HashSet;
                    import java.util.Set;
                    import org.springframework.stereotype.Component;

                    @Component
                    public class LockerStore {
                        private final Set<String> open = new HashSet<>();

                        public void open(String lockerCode) {
                            open.add(lockerCode);
                        }

                        public void close(String lockerCode) {
                            open.remove(lockerCode);
                        }
                    }
                    """);
            Files.writeString(dir.resolve("LockerTidier.java"), """
                    package com.example.e2e.locker;

                    import org.slf4j.Logger;
                    import org.slf4j.LoggerFactory;
                    import org.slf4j.MDC;
                    import org.springframework.stereotype.Component;

                    @Component
                    public class LockerTidier {
                        private static final Logger log = LoggerFactory.getLogger(LockerTidier.class);

                        public void tidy(String lockerCode) {
                            MDC.put("lockerCode", lockerCode.trim());
                            try {
                                log.info("Locker tidied");
                            } finally {
                                MDC.remove("lockerCode");
                            }
                        }
                    }
                    """);
            Files.writeString(dir.resolve("LockerController.java"), """
                    package com.example.e2e.locker;

                    import org.slf4j.MDC;
                    import org.springframework.web.bind.annotation.PathVariable;
                    import org.springframework.web.bind.annotation.PostMapping;
                    import org.springframework.web.bind.annotation.RestController;

                    @RestController
                    public class LockerController {
                        private final LockerStore store;
                        private final LockerTidier tidier;

                        public LockerController(LockerStore store, LockerTidier tidier) {
                            this.store = store;
                            this.tidier = tidier;
                        }

                        @PostMapping("/lockers/{lockerCode}/tidy")
                        public void tidy(@PathVariable String lockerCode) {
                            tidier.tidy(lockerCode);
                        }

                        @PostMapping("/lockers/{lockerCode}/open")
                        public void open(@PathVariable String lockerCode) {
                            store.open(lockerCode);
                        }

                        @PostMapping("/lockers/{lockerCode}/close")
                        public void close(@PathVariable String lockerCode) {
                            MDC.put("lockerCode", lockerCode);
                            try {
                                store.close(lockerCode);
                            } finally {
                                MDC.remove("lockerCode");
                            }
                        }

                        @PostMapping("/lockers/{lockerCode}/reopen")
                        public void reopen(@PathVariable String lockerCode) {
                            try (var mdc = MDC.putCloseable("lockerCode", lockerCode)) {
                                store.open(lockerCode);
                            }
                        }

                        @PostMapping("/lockers/{lockerCode}/swap")
                        public void swap(@PathVariable String lockerCode, String otherCode) {
                            store.close(lockerCode);
                            MDC.put("lockerCode", otherCode);
                            try {
                                store.open(otherCode);
                            } finally {
                                MDC.remove("lockerCode");
                            }
                        }
                    }
                    """);
        });
        // the started lines of the controller are written and applied: each lands inside the MDC scope its method opens with
        long filled = editDecisions(app.resolve(".logast/decisions/messages.yaml"), root -> fillMessages(root, (hint, where) ->
                hint.matches("Entry log for [^\"]*") && where.startsWith("src/main/java/com/example/e2e/locker/LockerController")
                        ? "Locker change started" : null, false));
        assertThat(filled).as("messages.yaml asks for the controller's started lines").isPositive();
        String third = run(app, "apply", app.toString(), "--auto", "--write", "--classpath-file", CLASSPATH);
        String trace = Files.readString(app.resolve(".logast/reports/apply-trace.txt"));
        assertThat(trace.contains("== pass 2") ? trace.substring(trace.indexOf("== pass 2")) : "")
                .as("pass 2 applies nothing:\n" + trace).doesNotContain("applying");
        String fourth = run(app, "apply", app.toString(), "--auto", "--write", "--classpath-file", CLASSPATH);
        assertThat(third + fourth).doesNotContain("CHECK FAILED");
        assertThat(fourth).as("a further apply changes nothing").contains("applied (0):").contains("files (0):");
        Files.writeString(app.resolve("OUTPUT.txt"), Files.readString(app.resolve("OUTPUT.txt"))
                + "\n=== after writing the controller's started lines\n" + third);
        compile(app);
        Map<String, String> actual = files(app);
        String controller = actual.get("src/main/java/com/example/e2e/locker/LockerController.java");
        assertThat(controller).as("#12: the started line is inside the scope the method opens with")
                .contains("try (var mdc = MdcScope.of(\"lockerCode\", lockerCode)) {\n            log.info(\"Locker change started\");")
                .doesNotContain("{\n        log.info(\"Locker change started\");\n        try (var mdc");
        String sweep = actual.get("src/main/java/com/example/e2e/locker/LockerSweepJob.java");
        assertThat(sweep).as("#79: a derived value is not the caller's: its scope stays (restoring) and is still logged")
                .contains("MdcScope.of(\"lockerCode\", slot)").contains("MdcScope.of(\"lockerCode\", lockerCode.strip())")
                .contains("MdcScope.of(\"lockerCode\", \"L-\" + lockerCode)").contains("MdcScope.of(\"lockerCode\", lockerCode.toLowerCase())")
                .as("#79: the caller's own value (the source parameter, unchanged) is unwrapped")
                .contains("void recount(String lockerCode) {\n        log.info(\"Locker recounted\");");
        assertThat(actual.get("src/main/java/com/example/e2e/locker/LockerTidier.java"))
                .contains("MdcScope.of(\"lockerCode\", lockerCode.trim())");
        assertThat(actual.get("FINDINGS.txt")).as("#79: each derived value is reported for a decision")
                .contains("sets it to slot, not provably").contains("sets it to lockerCode.strip(), not provably")
                .contains("sets it to \"L-\" + lockerCode, not provably").contains("sets it to lockerCode.toLowerCase(), not provably")
                .contains("sets it to lockerCode.trim(), not provably");
        golden(EXPECTED.resolveSibling("expected-mdc-placement"), actual);
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
        assertSameAsMain(withoutWarning);
    }

    /** Writes a text ("Pledge sweep <event>") into every messages.yaml entry that asks for one in a lifecycle form. */
    private static void writeTexts(Path app) throws IOException {
        java.util.regex.Pattern form = java.util.regex.Pattern.compile("^[^\"]*?must read as: \\{subject} (\\w+)");
        long filled = editDecisions(app.resolve(".logast/decisions/messages.yaml"), root -> fillMessages(root, (hint, where) -> {
            java.util.regex.Matcher m = form.matcher(hint);
            return m.find() ? "Pledge sweep " + m.group(1) : null;
        }, false));
        assertThat(filled).as("messages.yaml has texts to write").isPositive();
    }

    /** Edits a decision file as a person would: its entries as a tree, written back under the header logast wrote. */
    private static long editDecisions(Path file, java.util.function.ToLongFunction<com.fasterxml.jackson.databind.node.ObjectNode> edit)
            throws IOException {
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) dev.logast.apply.DecisionYaml.MAPPER.readTree(file.toFile());
        long n = edit.applyAsLong(root);
        Files.writeString(file, dev.logast.apply.DecisionYaml.text(dev.logast.apply.DecisionYaml.header(file), root));
        return n;
    }

    private static List<com.fasterxml.jackson.databind.node.ObjectNode> entries(com.fasterxml.jackson.databind.node.ObjectNode root) {
        List<com.fasterxml.jackson.databind.node.ObjectNode> out = new ArrayList<>();
        root.fields().forEachRemaining(e -> {
            if (!e.getKey().startsWith("_") && e.getValue() instanceof com.fasterxml.jackson.databind.node.ObjectNode entry) {
                out.add(entry);
            }
        });
        return out;
    }

    /**
     * Writes MSG of the entries whose only slot is an empty MSG (MSG, then its hint _MSG, then _where), where
     * {@code text} gives one for the hint and the place; returns how many were written.
     */
    private static long fillMessages(com.fasterxml.jackson.databind.node.ObjectNode root,
                                     java.util.function.BiFunction<String, String, String> text, boolean firstOnly) {
        long n = 0;
        for (var entry : entries(root)) {
            List<String> names = new ArrayList<>();
            entry.fieldNames().forEachRemaining(names::add);
            int at = names.indexOf("MSG");
            if (at < 0 || at + 2 >= names.size() || !names.get(at + 1).equals("_MSG") || !names.get(at + 2).equals("_where")
                    || !entry.path("MSG").asText("x").isEmpty()) {
                continue;
            }
            String msg = text.apply(entry.path("_MSG").asText(""), entry.path("_where").asText(""));
            if (msg != null) {
                entry.put("MSG", msg);
                n++;
                if (firstOnly) {
                    break;
                }
            }
        }
        return n;
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
        long reworded = editDecisions(app.resolve(".logast/decisions/messages.yaml"), root -> fillMessages(root, (hint, where) ->
                hint.matches("(?s)Reword this DEBUG message[^\"]*was: Charging order.*") ? "Charging the card" : null, true));
        assertThat(reworded).as("messages.yaml asks to reword the charge line").isPositive();
        Path job = app.resolve("src/main/java/com/example/e2e/pledge/PledgeCleanupJob.java");
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
        String result = actual.get("src/main/java/com/example/e2e/pledge/PledgeCleanupJob.java");
        assertThat(result).as("each text on its own line").contains("log.info(\"Pledge sweep started\")")
                .contains("log.info(\"Pledge sweep completed [pledgeCount={}]\"").contains("log.debug(\"Cleanup tick\")");
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
                "fill in .logast/decisions/messages.yaml and .logast/decisions/metrics.yaml", "logast apply . --auto --write", "git diff");
        Map<String, String> written = new TreeMap<>();
        files(app).forEach((k, v) -> {
            if (!v.equals(before.get(k))) {
                written.put(k, v);
            }
        });
        assertThat(written.keySet()).as("init changes no source file").noneMatch(k -> k.startsWith("src/"));
        assertThat(written).as("init does not create the files to fill in").doesNotContainKeys(".logast/decisions/messages.yaml", ".logast/decisions/metrics.yaml");
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

    /**
     * #87: the same in dependency mode (the default: no {@code support:} block). A pack whose fixes use the support
     * classes adds the logging-support starter itself, and its fixes are applied, not left as reports for want of a
     * starter only another pack would add. Packs whose fixes use none (resilience, audit) leave the build file alone.
     * The starter is not on the classpath logast reads (this run adds it); javac gets its classes, as the service's
     * build does once the dependency resolves.
     * <p>#93: the starter switches nothing on by itself (its tests); each pack writes the properties of the starter
     * behaviours it owns, and only those. The service sets no console log format here, so a pack other than logging
     * leaves the log format as it was: neither the format nor the starter's log defaults are switched on.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "packs: [{0}]")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"logging", "tracing", "metrics", "resilience", "errors", "audit"})
    void eachPackWorksOnItsOwnWithTheStarterAsDependency(String pack) throws IOException {
        starterClasses = Path.of(System.getProperty("logast.e2e.support"));
        Path app = tmp.resolve("app");
        Map<String, String> actual = migrate(app, CLASSPATH, a -> {
            Path yml = a.resolve("logast.yml");
            String text = Files.readString(yml);
            String only = text.replaceFirst("(?m)^packs: .*$", "packs: [" + pack + "]").replace("support:\n  mode: vendor\n", "");
            assertThat(only).as("the scenario selects its packs, in dependency mode").contains("packs: [" + pack + "]")
                    .doesNotContain("support:");
            Files.writeString(yml, only);
            Path config = a.resolve("src/main/resources/application.yml");
            String unformatted = Files.readString(config).replace("logging:\n  structured:\n    format:\n      console: ecs\n", "");
            assertThat(unformatted).as("the service sets no console log format").doesNotContain("console:");
            Files.writeString(config, unformatted);
        });
        assertThat(app.resolve("src/main/java/com/example/e2e/logging")).as("dependency mode copies no support class").doesNotExist();
        boolean usesSupport = !Set.of("resilience", "audit").contains(pack);
        assertThat(actual.get("build.gradle.kts").contains("dev.logast:logging-support"))
                .as("the starter comes with a pack whose fixes use it, and only with one").isEqualTo(usesSupport);
        String config = actual.get("src/main/resources/application.yml");
        Map<String, List<String>> owned = Map.of("logging", List.of("log-defaults", "mdc-hygiene", "context-propagation"),
                "tracing", List.of("context-propagation", "batch-trace"), "errors", List.of("problem-trace-id"));
        java.util.regex.Matcher block = java.util.regex.Pattern.compile("(?m)^logging-support:\\R((?: .*\\R?)*)").matcher(config);
        assertThat(java.util.regex.Pattern.compile("(?m)^  ([\\w-]+):\\R    enabled: true$").matcher(block.find() ? block.group(1) : "")
                .results().map(m -> m.group(1)).toList())
                .as("the starter behaviours switched on are the pack's own").containsExactlyInAnyOrderElementsOf(owned.getOrDefault(pack, List.of()));
        assertThat(config.contains("console:")).as("only the logging pack sets the console log format").isEqualTo(pack.equals("logging"));
        if (pack.equals("errors")) {
            assertThat(config).contains("problem-trace-id:\n    enabled: true");
            assertThat(actual.get("FINDINGS.txt")).as("applied, not left as a report").doesNotContain("ERROR_RESPONSE_UNTRACED");
            golden(EXPECTED.resolveSibling("expected-errors-pack-dependency"), actual);
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
        assertBoot3PropertyNames(actual);
        assertThat(actual.get("FINDINGS.txt")).as("the Boot 3.5 endpoint property").contains("management.otlp.tracing.endpoint");
        golden(EXPECTED.resolveSibling("expected-boot3"), actual);
    }

    /**
     * #97: a Spring Boot 3.5 service gets Boot 3.5 property names only, also where logast's classpath is a Boot 4 one.
     * The names Boot 4.0 renamed (application properties appendix, docs.spring.io/spring-boot/{3.5,4.0}/appendix/
     * application-properties) must appear in no file of the result, as config or as advice. The names the tracing pack
     * and the service identity write otherwise (management.opentelemetry.resource-attributes, management.tracing.sampling.*,
     * management.observations.annotations.enabled, spring.kafka/rabbitmq.*.observation-enabled, spring.application.version,
     * logging.structured.json.add, management.metrics.tags) are the same in 3.5 and 4.x.
     */
    private static void assertBoot3PropertyNames(Map<String, String> actual) {
        List<String> boot4Only = List.of("management.opentelemetry.tracing.export", "MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT",
                "management.tracing.export", "spring.http.clients", "clients:\n      connect-timeout");
        actual.forEach((file, text) -> boot4Only.forEach(name ->
                assertThat(text).as(file + " names only Spring Boot 3.5 properties").doesNotContain(name)));
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
        assertBoot3PropertyNames(actual);
        golden(EXPECTED.resolveSibling("expected-boot3-conflict"), actual);
    }

    /** The scenario's classpath without the OpenTelemetry starter and what it brings: logast has to add it. */
    private Path classpathWithoutOtelStarter() throws IOException {
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
        return classpath;
    }

    @Test
    void springBoot4UsesOpenTelemetryStarter() throws IOException {
        Path classpath = classpathWithoutOtelStarter();
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

    /**
     * #80: the dependencies live in a script the build file applies (Groovy, apply from: 'gradle/dependencies.gradle').
     * The OpenTelemetry starter goes into that script's dependencies block, in its DSL; what it already declares is
     * seen there and not added again, and the build file stays as it is.
     */
    @Test
    void dependenciesGoIntoTheScriptTheBuildFileApplies() throws IOException {
        Path classpath = classpathWithoutOtelStarter();
        String buildGradle = """
                plugins {
                    id 'java'
                    id 'org.springframework.boot' version '4.1.2'
                }

                repositories { mavenCentral() }

                apply from: 'gradle/dependencies.gradle'
                """;
        Map<String, String> actual = migrate(tmp.resolve("app"), classpath.toString(), app -> {
            Files.delete(app.resolve("build.gradle.kts"));
            Files.writeString(app.resolve("build.gradle"), buildGradle);
            Files.createDirectories(app.resolve("gradle"));
            Files.writeString(app.resolve("gradle/dependencies.gradle"), """
                    dependencies {
                        implementation platform('org.springframework.boot:spring-boot-dependencies:4.1.2')
                        implementation 'org.springframework.boot:spring-boot-starter-webmvc'
                        implementation 'org.springframework:spring-jdbc'
                        implementation 'io.micrometer:micrometer-observation'
                        implementation 'io.micrometer:context-propagation'
                    }
                    """);
        });

        assertThat(actual.get("build.gradle")).as("the build file has no dependencies block: left as is").isEqualTo(buildGradle);
        String dependencies = actual.get("gradle/dependencies.gradle");
        assertThat(dependencies).contains("    implementation 'org.springframework.boot:spring-boot-starter-opentelemetry'\n")
                .doesNotContain("micrometer-tracing-bridge-otel");
        assertThat(dependencies.split("spring-boot-starter-opentelemetry", -1)).hasSize(2);
        assertThat(dependencies.split("micrometer-observation", -1)).as("declared in the applied script: not added again").hasSize(2);
        assertThat(dependencies.split("context-propagation", -1)).hasSize(2);
        golden(EXPECTED.resolveSibling("expected-applied-dependencies"), actual);
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
        assertThat(actual.get(".logast/decisions/messages.yaml")).contains("FMT-").contains("doing");
        assertThat(actual.get(".logast/decisions/metrics.yaml")).contains("OUM-").contains("_where");
        golden(EXPECTED.resolveSibling("expected-source-markers"), actual);
    }

    /**
     * #72: .logast/reports/coverage.txt accounts for every log call: fixed, reported, ignored, skipped with the reason recorded
     * where the rule passes it over, or checked. e2e/before has fixed, reported, checked and some skips (a log helper,
     * a constant template, TRACE, the copied support classes); the shapes it lacks are added here: a protected message,
     * another logging API, an ignore comment, a fluent call with and without a message, a message from an argument.
     */
    @Test
    void everyLogCallIsAccountedFor() throws IOException {
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, app -> {
            Path yml = app.resolve("logast.yml");
            Files.writeString(yml, Files.readString(yml).replace("logging:\n",
                    "logging:\n  protectedMessages: ['^Payment gateway unavailable']\n"));
            Path dir = app.resolve("src/main/java/com/example/e2e/ledger");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("GatewayCalls.java"), """
                    package com.example.e2e.ledger;

                    import org.slf4j.Logger;
                    import org.slf4j.LoggerFactory;

                    class GatewayCalls {
                        private static final Logger log = LoggerFactory.getLogger(GatewayCalls.class);

                        void charge(String gatewayId, String reply) {
                            log.warn("Payment gateway unavailable [gatewayId={}]", gatewayId);
                            log.debug("{}", reply);
                            log.atDebug().addKeyValue("gatewayId", gatewayId).log("Gateway reply received");
                            log.atDebug().addKeyValue("gatewayId", gatewayId).log();
                            log.info("Gateway {} charged", gatewayId); // logast:ignore LOG_FORMAT METRIC_OUTCOME_UNMEASURED
                        }

                        void store(String gatewayRef) {
                            log.atInfo().addKeyValue("gateway_ref", gatewayRef).log("Gateway reply stored");
                        }
                    }
                    """);
            Files.writeString(dir.resolve("LegacyGateway.java"), """
                    package com.example.e2e.ledger;

                    import java.util.logging.Logger;

                    class LegacyGateway {
                        private static final Logger log = Logger.getLogger(LegacyGateway.class.getName());

                        void refund(String gatewayId) {
                            log.info("Gateway refund requested");
                        }
                    }
                    """);
        });

        String coverage = actual.get(".logast/reports/coverage.txt");
        assertThat(coverage).as("every outcome").contains("  fixed    ", "  reported ", "  ignored  LOG_FORMAT", "  checked  ");
        assertThat(coverage).as("every skip reason").contains("protected message", "message not a literal", "message comes from an argument",
                "fluent call", "no message", "TRACE level", "inside a log helper", "logging-support code",
                "other logging API: java.util.logging");
        assertThat(actual.get("src/main/java/com/example/e2e/ledger/GatewayCalls.java"))
                .as("#22: a property key gets the name a [k=v] key would (keyStyle camel)")
                .contains("log.atInfo().addKeyValue(\"gatewayRef\", gatewayRef).log(").doesNotContain("gateway_ref");
        assertThat(actual.get("FINDINGS.txt")).as("the one coverage line (#101)").containsPattern("(?m)^Log lines: \\d+ — .*\\d+ not checked \\(.logast/reports/coverage.txt\\)$");
        golden(EXPECTED.resolveSibling("expected-log-call-coverage"), actual);
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
        assertThat(actual.get(".logast/decisions/messages.yaml")).contains("FMT-").contains("orderId");
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

    /**
     * #13: an argument with no letter or digit ("()", "-", '(') has no name to derive a key from. The empty key wrote
     * "[binCode={}, ={}]" (logged as "[binCode=B1, =()]"), which is not house format, so every apply prepended "[=]".
     */
    @Test
    void punctuationArgumentGetsANamedSuffixKey() throws IOException {
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, app -> {
            Path source = app.resolve("src/main/java/com/example/e2e/review/BinChecker.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, """
                    package com.example.e2e.review;

                    import org.slf4j.Logger;
                    import org.slf4j.LoggerFactory;

                    class BinChecker {
                        private static final Logger log = LoggerFactory.getLogger(BinChecker.class);

                        void check(String binCode) {
                            log.info("Bin checked {} {}", binCode, "()");
                        }

                        void empty(char marker) {
                            log.info("Bin emptied: {}", '-');
                        }
                    }
                    """);
        });

        String source = actual.get("src/main/java/com/example/e2e/review/BinChecker.java");
        assertThat(source)
                .contains("log.info(\"Bin checked [binCode={}, value={}]\", binCode, \"()\");\n")
                .contains("log.info(\"Bin emptied [value={}]\", '-');\n")
                .doesNotContain("[=").doesNotContain(", ={}");
        assertThat(actual.get("FINDINGS.txt")).doesNotContain("[=").doesNotContain(", ={}");
        golden(EXPECTED.resolveSibling("expected-punctuation-argument"), actual);
    }

    /**
     * #64: resolver beans put the value they resolve into MDC while they are created (constructor, @PostConstruct) and a
     * job started at startup (CommandLineRunner, ApplicationReadyEvent) resolves the same value again and puts it itself.
     * The startup writes stick to the startup thread: the context belongs at the job, with the values of that run.
     */
    @Test
    void resolverContextMovesToTheJob() throws IOException {
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, app -> {
            Path yml = app.resolve("logast.yml");
            Files.writeString(yml, Files.readString(yml).replace("vocabulary:\n", """
                    vocabulary:
                      country:
                        mdc:
                          key: countryCode
                          sources:
                            params: [countryCode]
                      week:
                        mdc:
                          key: targetWeek
                          sources:
                            params: [targetWeek]
                    """));
            Path dir = app.resolve("src/main/java/com/example/e2e/weekly");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("CountryCodeResolver.java"), """
                    package com.example.e2e.weekly;

                    import org.slf4j.Logger;
                    import org.slf4j.LoggerFactory;
                    import org.slf4j.MDC;
                    import org.springframework.beans.factory.annotation.Value;
                    import org.springframework.stereotype.Component;

                    @Component
                    public class CountryCodeResolver {
                        private static final Logger log = LoggerFactory.getLogger(CountryCodeResolver.class);
                        private final String countryCode;

                        public CountryCodeResolver(@Value("${weekly.country:HU}") String countryCode) {
                            this.countryCode = countryCode;
                            MDC.put("countryCode", countryCode);
                            log.info("Country code resolved [countryCode={}]", countryCode);
                        }

                        public String countryCode() {
                            return countryCode;
                        }
                    }
                    """);
            Files.writeString(dir.resolve("TargetWeekResolver.java"), """
                    package com.example.e2e.weekly;

                    import jakarta.annotation.PostConstruct;
                    import java.time.LocalDate;
                    import java.time.temporal.IsoFields;
                    import org.slf4j.Logger;
                    import org.slf4j.LoggerFactory;
                    import org.slf4j.MDC;
                    import org.springframework.stereotype.Component;

                    @Component
                    public class TargetWeekResolver {
                        private static final Logger log = LoggerFactory.getLogger(TargetWeekResolver.class);
                        private String targetWeek;

                        @PostConstruct
                        void init() {
                            targetWeek = String.valueOf(LocalDate.now().get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
                            MDC.put("targetWeek", targetWeek);
                            log.info("Target week resolved [targetWeek={}]", targetWeek);
                        }

                        public String forCountry(String countryCode) {
                            log.info("Target week checked [countryCode={}]", countryCode);
                            return targetWeek;
                        }

                        public String targetWeek() {
                            return targetWeek;
                        }
                    }
                    """);
            Files.writeString(dir.resolve("WeeklyCloseRunner.java"), """
                    package com.example.e2e.weekly;

                    import org.slf4j.Logger;
                    import org.slf4j.LoggerFactory;
                    import org.slf4j.MDC;
                    import org.springframework.boot.CommandLineRunner;
                    import org.springframework.stereotype.Component;

                    @Component
                    public class WeeklyCloseRunner implements CommandLineRunner {
                        private static final Logger log = LoggerFactory.getLogger(WeeklyCloseRunner.class);
                        private final CountryCodeResolver countryCodeResolver;
                        private final TargetWeekResolver targetWeekResolver;

                        public WeeklyCloseRunner(CountryCodeResolver countryCodeResolver, TargetWeekResolver targetWeekResolver) {
                            this.countryCodeResolver = countryCodeResolver;
                            this.targetWeekResolver = targetWeekResolver;
                        }

                        @Override
                        public void run(String... args) {
                            MDC.put("countryCode", countryCodeResolver.countryCode());
                            MDC.put("targetWeek", targetWeekResolver.targetWeek());
                            log.info("Weekly close started");
                            close();
                            log.info("Weekly close completed");
                            MDC.clear();
                        }

                        private void close() {
                            log.debug("Weekly close rows written [week={}]", targetWeekResolver.forCountry(countryCodeResolver.countryCode()));
                        }
                    }
                    """);
            Files.writeString(dir.resolve("WeeklyReportStarter.java"), """
                    package com.example.e2e.weekly;

                    import org.slf4j.Logger;
                    import org.slf4j.LoggerFactory;
                    import org.slf4j.MDC;
                    import org.springframework.boot.context.event.ApplicationReadyEvent;
                    import org.springframework.context.event.EventListener;
                    import org.springframework.stereotype.Component;

                    @Component
                    public class WeeklyReportStarter {
                        private static final Logger log = LoggerFactory.getLogger(WeeklyReportStarter.class);
                        private final TargetWeekResolver targetWeekResolver;

                        public WeeklyReportStarter(TargetWeekResolver targetWeekResolver) {
                            this.targetWeekResolver = targetWeekResolver;
                        }

                        @EventListener(ApplicationReadyEvent.class)
                        public void onReady() {
                            MDC.put("targetWeek", targetWeekResolver.targetWeek());
                            try {
                                log.info("Weekly report started");
                            } finally {
                                MDC.remove("targetWeek");
                            }
                        }
                    }
                    """);
        });
        String weekly = "src/main/java/com/example/e2e/weekly/";
        assertThat(actual.get(weekly + "CountryCodeResolver.java") + actual.get(weekly + "TargetWeekResolver.java"))
                .as("no MDC write is left in the resolvers: the startup thread keeps no context")
                .doesNotContain("MDC").doesNotContain("MdcScope")
                .as("their startup logs have no scope to carry the value: it stays in the line")
                .contains("Country code resolved [countryCode={}]").contains("Target week resolved [targetWeek={}]");
        assertThat(actual.get(weekly + "WeeklyCloseRunner.java")).as("the job scopes the values of its run")
                .contains("try (var mdc = MdcScope.of(\"countryCode\", countryCodeResolver.countryCode(), \"targetWeek\", "
                        + "targetWeekResolver.targetWeek())) {").doesNotContain("MDC.clear()");
        assertThat(actual.get(weekly + "WeeklyReportStarter.java")).as("the startup hook scopes its own run; #20: in the "
                        + "ObservedContext chain, not in an MdcScope inside it")
                .contains("        ObservedContext.entrypoint(WeeklyMetrics.WEEKLY_REPORT_STARTER_ON_READY)\n"
                        + "                .mdc(\"targetWeek\", targetWeekResolver.targetWeek())\n")
                .doesNotContain("MdcScope.of");
        golden(EXPECTED.resolveSibling("expected-resolver-context"), actual);
    }

    /**
     * #54: a project whose files end their lines with CRLF (written on Windows) gives the LF result with CRLF: edits
     * keep the file's line ending and the lines they insert use it too, so no file ends up with mixed line endings.
     */
    @Test
    void crlfFilesKeepTheirLineEndings() throws IOException {
        List<String> before = new ArrayList<>();
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, app -> {
            try (Stream<Path> s = Files.walk(app)) {
                for (Path p : s.filter(Files::isRegularFile).toList()) {
                    before.add(app.relativize(p).toString().replace('\\', '/'));
                    Files.writeString(p, Files.readString(p).replace("\n", "\r\n"));
                }
            }
        });
        assertThat(before).hasSizeGreaterThan(40);
        for (String rel : before) {
            if (actual.containsKey(rel)) {   // logast's own support classes are not compared
                assertThat(actual.get(rel)).as(rel + " ends every line with CRLF").doesNotContainPattern("[^\r]\n");
            }
        }
        Map<String, String> lf = new TreeMap<>();
        actual.forEach((k, v) -> lf.put(k, v.replace("\r\n", "\n")));
        assertSameAsMain(lf);
    }

    /**
     * #54: a file is read in its charset and written back in it. A Java file in ISO-8859-1 (an older service with
     * {@code options.encoding = "ISO-8859-1"}) is edited, and every byte outside the edits stays as it was (0xF5 is "õ"
     * in Latin-1 and "ő" in Latin-2: either way it survives); an application.yml with a UTF-8 BOM is edited and keeps
     * its one BOM. Every file must equal e2e/expected-encodings.
     */
    @Test
    void filesKeepTheirEncoding() throws IOException {
        String legacy = "src/main/java/com/example/e2e/legacy/PriceListImport.java";
        String comment = "        // Preisliste übernehmen: Größe, café, õ (ISO-8859-1)\n";
        sourceEncoding = "ISO-8859-1";
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, app -> {
            Path source = app.resolve(legacy);
            Files.createDirectories(source.getParent());
            Files.write(source, ("""
                    package com.example.e2e.legacy;

                    import org.slf4j.Logger;
                    import org.slf4j.LoggerFactory;
                    import org.springframework.stereotype.Component;

                    /** Imports price lists (Übersicht). */
                    @Component
                    public class PriceListImport {
                        private static final Logger log = LoggerFactory.getLogger(PriceListImport.class);

                        public void load(String listId) {
                    """ + comment + """
                            log.info("Liste importée: " + listId);
                        }
                    }
                    """).getBytes(StandardCharsets.ISO_8859_1));
            Path yml = app.resolve("src/main/resources/application.yml");
            Files.writeString(yml, "﻿" + Files.readString(yml));
        });
        byte[] bytes = Files.readAllBytes(tmp.resolve("app").resolve(legacy));
        assertThat(utf8(bytes)).as("still ISO-8859-1").isNull();
        assertThat(new String(bytes, StandardCharsets.ISO_8859_1)).as("the comment byte for byte, the edit in ISO-8859-1")
                .contains("/** Imports price lists (Übersicht). */\n").contains(comment)
                .contains("Liste importée").doesNotContain("\" + listId");
        String yml = utf8(Files.readAllBytes(tmp.resolve("app/src/main/resources/application.yml")));
        assertThat(yml).as("edited, with its one BOM at the start")
                .startsWith("﻿spring:").containsOnlyOnce("﻿").contains("observation-enabled: true");
        golden(EXPECTED.resolveSibling("expected-encodings"), actual);
    }

    /**
     * #52: exceptions that do not come from {@code throw new X(…)} on the throw line. {@code orElseThrow} with a lambda
     * or a constructor reference and a factory method ({@code throw Errors.expired(id)}) still reach the client as a
     * 500 (UNMAPPED_EXCEPTION); a factory called in a catch without the caught exception drops its cause (CAUSE_DROPPED).
     */
    @Test
    void followsExceptionsNotThrownWithNew() throws IOException {
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, app -> {
            Path dir = app.resolve("src/main/java/com/example/e2e/voucher");
            Files.createDirectories(dir);
            for (String name : List.of("VoucherMissing", "VoucherUnknown", "VoucherExpired", "VoucherStoreFailure")) {
                Files.writeString(dir.resolve(name + ".java"), """
                        package com.example.e2e.voucher;

                        public class %s extends RuntimeException {
                            public %s() {
                                super();
                            }

                            public %s(String message) {
                                super(message);
                            }

                            public %s(String message, Throwable cause) {
                                super(message, cause);
                            }
                        }
                        """.formatted(name, name, name, name));
            }
            Files.writeString(dir.resolve("VoucherGone.java"), """
                    package com.example.e2e.voucher;

                    import org.springframework.http.HttpStatus;
                    import org.springframework.web.bind.annotation.ResponseStatus;

                    @ResponseStatus(HttpStatus.GONE)
                    public class VoucherGone extends RuntimeException {
                        public VoucherGone(String message) {
                            super(message);
                        }
                    }
                    """);
            Files.writeString(dir.resolve("VoucherErrors.java"), """
                    package com.example.e2e.voucher;

                    final class VoucherErrors {
                        private VoucherErrors() {
                        }

                        static VoucherExpired expired(String voucherId) {
                            return new VoucherExpired("voucher expired: " + voucherId);
                        }

                        static VoucherStoreFailure storeFailed(String voucherId) {
                            return new VoucherStoreFailure("voucher not stored: " + voucherId);
                        }

                        static VoucherStoreFailure storeFailed(String voucherId, Throwable cause) {
                            return new VoucherStoreFailure("voucher not stored: " + voucherId, cause);
                        }
                    }
                    """);
            Files.writeString(dir.resolve("VoucherStore.java"), """
                    package com.example.e2e.voucher;

                    import java.io.IOException;
                    import java.util.Optional;
                    import org.springframework.stereotype.Component;

                    @Component
                    class VoucherStore {
                        Optional<String> findById(String voucherId) {
                            return Optional.empty();
                        }

                        Optional<String> findCode(String voucherId) {
                            return Optional.empty();
                        }

                        boolean expired(String voucherId) {
                            return voucherId.isEmpty();
                        }

                        void write(String voucherId) throws IOException {
                        }
                    }
                    """);
            Files.writeString(dir.resolve("VoucherController.java"), """
                    package com.example.e2e.voucher;

                    import java.io.IOException;
                    import org.springframework.web.bind.annotation.GetMapping;
                    import org.springframework.web.bind.annotation.PathVariable;
                    import org.springframework.web.bind.annotation.PostMapping;
                    import org.springframework.web.bind.annotation.RestController;

                    @RestController
                    class VoucherController {
                        private final VoucherStore store;

                        VoucherController(VoucherStore store) {
                            this.store = store;
                        }

                        @GetMapping("/vouchers/{voucherId}")
                        String find(@PathVariable String voucherId) {
                            return store.findById(voucherId).orElseThrow(() -> new VoucherMissing(voucherId));
                        }

                        @GetMapping("/vouchers/{voucherId}/code")
                        String code(@PathVariable String voucherId) {
                            return store.findCode(voucherId).orElseThrow(VoucherUnknown::new);
                        }

                        @GetMapping("/vouchers/{voucherId}/archived")
                        String archived(@PathVariable String voucherId) {
                            return store.findById(voucherId).orElseThrow(() -> new VoucherGone(voucherId));
                        }

                        @PostMapping("/vouchers/{voucherId}/allot")
                        void allot(@PathVariable String voucherId) {
                            if (store.expired(voucherId)) {
                                throw VoucherErrors.expired(voucherId);
                            }
                        }

                        @PostMapping("/vouchers/{voucherId}/store")
                        void store(@PathVariable String voucherId) {
                            try {
                                store.write(voucherId);
                            } catch (IOException e) {
                                throw VoucherErrors.storeFailed(voucherId);
                            }
                        }

                        @PostMapping("/vouchers/{voucherId}/restore")
                        void restore(@PathVariable String voucherId) {
                            try {
                                store.write(voucherId);
                            } catch (IOException | RuntimeException e) {
                                throw VoucherErrors.storeFailed(voucherId, e);
                            }
                        }
                    }
                    """);
        });

        String findings = actual.get("FINDINGS.txt");
        assertThat(findings).as("orElseThrow with a lambda")
                .contains("VoucherMissing is thrown on HTTP paths (1×) but no handler or @ResponseStatus maps it");
        assertThat(findings).as("orElseThrow with a constructor reference")
                .contains("VoucherUnknown is thrown on HTTP paths (1×) but no handler or @ResponseStatus maps it");
        assertThat(findings).as("a factory method on the throw line")
                .contains("VoucherExpired is thrown on HTTP paths (1×) but no handler or @ResponseStatus maps it");
        // restore's throw sits in a catch (RuntimeException) of its own try: thrown from the catch, so not caught there
        assertThat(findings).as("the factory in a catch, with and without the cause")
                .contains("VoucherStoreFailure is thrown on HTTP paths (2×) but no handler or @ResponseStatus maps it");
        assertThat(findings).as("@ResponseStatus maps it").doesNotContain("VoucherGone is thrown");
        assertThat(findings).as("a factory called without the caught exception drops the cause")
                .contains("catch (IOException e) throws VoucherErrors.storeFailed(voucherId) without e as its cause");
        assertThat(findings).as("the factory that gets the cause passes it on")
                .doesNotContain("VoucherErrors.storeFailed(voucherId, e) without");
        golden(EXPECTED.resolveSibling("expected-exceptions-not-thrown-with-new"), actual);
    }

    /** #91: the shapes of the errors pack's newer checks, in a parcel service. */
    private static final IoAction PARCEL_ERRORS = app -> {
        Path dir = app.resolve("src/main/java/com/example/e2e/parcel");
        Files.createDirectories(dir);
        for (String name : List.of("ParcelHeld", "ParcelLate", "ParcelLost")) {
            Files.writeString(dir.resolve(name + ".java"), """
                    package com.example.e2e.parcel;

                    public class %s extends RuntimeException {
                        public %s(String message) {
                            super(message);
                        }
                    }
                    """.formatted(name, name));
        }
        Files.writeString(dir.resolve("ParcelAnswer.java"), """
                package com.example.e2e.parcel;

                public record ParcelAnswer(String code) {
                }
                """);
        // a catch-all that logs nothing; two handlers that answer 200; one that sets its status
        Files.writeString(dir.resolve("ParcelErrors.java"), """
                package com.example.e2e.parcel;

                import org.springframework.http.HttpStatus;
                import org.springframework.http.ProblemDetail;
                import org.springframework.http.ResponseEntity;
                import org.springframework.web.bind.annotation.ExceptionHandler;
                import org.springframework.web.bind.annotation.ResponseStatus;
                import org.springframework.web.bind.annotation.RestControllerAdvice;

                @RestControllerAdvice
                class ParcelErrors {
                    @ExceptionHandler(Exception.class)
                    ProblemDetail unexpected(Exception e) {
                        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
                    }

                    @ExceptionHandler(ParcelHeld.class)
                    ParcelAnswer held(ParcelHeld e) {
                        return new ParcelAnswer("held");
                    }

                    @ExceptionHandler(ParcelLate.class)
                    ResponseEntity<String> late(ParcelLate e) {
                        return ResponseEntity.ok("late");
                    }

                    @ExceptionHandler(ParcelLost.class)
                    @ResponseStatus(HttpStatus.GONE)
                    ParcelAnswer lost(ParcelLost e) {
                        return new ParcelAnswer("lost");
                    }
                }
                """);
        // a downstream call on an HTTP path nothing maps, and one its caller catches
        Files.writeString(dir.resolve("ParcelClient.java"), """
                package com.example.e2e.parcel;

                import org.springframework.stereotype.Component;
                import org.springframework.web.client.RestClient;
                import org.springframework.web.client.RestClientException;

                @Component
                class ParcelClient {
                    private final RestClient rest;

                    ParcelClient(RestClient.Builder builder) {
                        this.rest = builder.baseUrl("http://parcels").build();
                    }

                    String track(String parcelCode) {
                        return rest.get().uri("/parcels/{parcelCode}", parcelCode).retrieve().body(String.class);
                    }

                    String carrier(String parcelCode) {
                        try {
                            return rest.get().uri("/parcels/{parcelCode}/carrier", parcelCode).retrieve().body(String.class);
                        } catch (RestClientException e) {
                            throw new ParcelLate("carrier unknown for " + parcelCode);
                        }
                    }
                }
                """);
        // checked exceptions out of a transaction: one commits by default, one has a rollback rule
        Files.writeString(dir.resolve("ParcelLedger.java"), """
                package com.example.e2e.parcel;

                import java.io.IOException;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;

                @Service
                class ParcelLedger {
                    @Transactional
                    public void archive(String parcelCode) throws IOException {
                        if (parcelCode.isBlank()) {
                            throw new IOException("no parcel code");
                        }
                    }

                    @Transactional(rollbackFor = IOException.class)
                    public void restore(String parcelCode) throws IOException {
                        if (parcelCode.isBlank()) {
                            throw new IOException("no parcel code");
                        }
                    }
                }
                """);
        Files.writeString(dir.resolve("ParcelController.java"), """
                package com.example.e2e.parcel;

                import java.io.IOException;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                class ParcelController {
                    private final ParcelClient client;
                    private final ParcelLedger ledger;

                    ParcelController(ParcelClient client, ParcelLedger ledger) {
                        this.client = client;
                        this.ledger = ledger;
                    }

                    @GetMapping("/parcels/{parcelCode}")
                    String track(@PathVariable String parcelCode) {
                        return client.track(parcelCode);
                    }

                    @GetMapping("/parcels/{parcelCode}/carrier")
                    String carrier(@PathVariable String parcelCode) {
                        return client.carrier(parcelCode);
                    }

                    @PostMapping("/parcels/{parcelCode}/archive")
                    void archive(@PathVariable String parcelCode) throws IOException {
                        ledger.archive(parcelCode);
                        ledger.restore(parcelCode);
                    }
                }
                """);
    };

    /**
     * #91: the errors pack's newer checks. A catch-all handler that logs nothing gets an ERROR line with the exception
     * (UNEXPECTED_ERROR_UNLOGGED, AUTO); handlers that answer 200 (a body object, ResponseEntity.ok) are reported, the
     * one with @ResponseStatus is not (ERROR_HANDLER_STATUS_OK); a RestClient call on an HTTP path that no handler
     * maps is reported as a downstream failure answered 500, the call its caller catches is not counted
     * (UNMAPPED_EXCEPTION); a @Transactional method throwing a checked exception without rollbackFor is reported, the
     * one with it is not (TX_COMMITS_ON_CHECKED). The same checks with only the errors pack selected. Every file must
     * equal e2e/expected-errors-pack.
     */
    @Test
    void errorsPackFindsUnloggedHandlersStatusOkDownstreamFailuresAndCommittingTransactions() throws IOException {
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, PARCEL_ERRORS);
        String handlers = actual.get("src/main/java/com/example/e2e/parcel/ParcelErrors.java");
        assertThat(handlers).as("the catch-all logs the exception")
                .contains("log.error(\"Request failed unexpectedly\", e);");
        String findings = actual.get("FINDINGS.txt");
        assertThat(findings).as("a body object without @ResponseStatus")
                .contains("Handler for ParcelHeld returns ParcelAnswer without @ResponseStatus: the client gets 200");
        assertThat(findings).as("ResponseEntity.ok").contains("Handler for ParcelLate answers ResponseEntity.ok");
        assertThat(findings).as("@ResponseStatus sets it").doesNotContain("Handler for ParcelLost");
        assertThat(findings).as("one unmapped downstream call; the caught one is not counted")
                .contains("Failures of downstream calls (RestClient / RestTemplate, 1× on HTTP paths");
        assertThat(findings).as("checked exception without a rollback rule")
                .contains("ParcelLedger.archive is @Transactional and throws IOException")
                .doesNotContain("ParcelLedger.restore is @Transactional");
        golden(EXPECTED.resolveSibling("expected-errors-pack"), actual);

        // the errors pack alone: the same checks, the log line brings its own logger
        Map<String, String> alone = migrate(tmp.resolve("errors-only"), CLASSPATH, app -> {
            PARCEL_ERRORS.run(app);
            Path yml = app.resolve("logast.yml");
            Files.writeString(yml, Files.readString(yml).replaceFirst("(?m)^packs: .*$", "packs: [errors]"));
        });
        assertThat(alone.get("src/main/java/com/example/e2e/parcel/ParcelErrors.java"))
                .contains("log.error(\"Request failed unexpectedly\", e);");
        assertThat(alone.get("FINDINGS.txt"))
                .contains("Handler for ParcelHeld returns ParcelAnswer without @ResponseStatus")
                .contains("Handler for ParcelLate answers ResponseEntity.ok")
                .contains("Failures of downstream calls (RestClient / RestTemplate, 1× on HTTP paths")
                .contains("ParcelLedger.archive is @Transactional and throws IOException");
    }

    /** #91 / #52: a library on the classpath whose @RestControllerAdvice maps its base exception (the sources do not show it). */
    private static final Map<String, String> PLATFORM_ERRORS_LIBRARY = Map.of(
            "org/orchardlib/errors/NotFound.java", """
                    package org.orchardlib.errors;

                    public class NotFound extends RuntimeException {
                        public NotFound(String message) {
                            super(message);
                        }
                    }
                    """,
            "org/orchardlib/errors/PlatformErrors.java", """
                    package org.orchardlib.errors;

                    import org.springframework.http.HttpStatus;
                    import org.springframework.http.ProblemDetail;
                    import org.springframework.web.bind.annotation.ExceptionHandler;
                    import org.springframework.web.bind.annotation.RestControllerAdvice;

                    @RestControllerAdvice
                    public class PlatformErrors {
                        @ExceptionHandler(NotFound.class)
                        ProblemDetail notFound(NotFound e) {
                            return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
                        }
                    }
                    """);

    /** The library compiled into a jar (javac gets it too), and a classpath file of the e2e classpath plus that jar. */
    private Path platformErrorsClasspath() throws IOException {
        Path src = tmp.resolve("platform-errors/src");
        Path out = Files.createDirectories(tmp.resolve("platform-errors/classes"));
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "-cp",
                String.join(java.io.File.pathSeparator, Files.readAllLines(Path.of(CLASSPATH)).stream().filter(l -> !l.isBlank()).toList())));
        for (Map.Entry<String, String> e : new TreeMap<>(PLATFORM_ERRORS_LIBRARY).entrySet()) {
            Path f = src.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
            args.add(f.toString());
        }
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, err, args.toArray(String[]::new)))
                .as("the platform errors library compiles:\n" + err).isZero();
        Path jar = tmp.resolve("platform-errors/platform-errors.jar");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar)); Stream<Path> s = Files.walk(out)) {
            for (Path c : s.filter(Files::isRegularFile).sorted().toList()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(out.relativize(c).toString().replace('\\', '/')));
                zip.write(Files.readAllBytes(c));
                zip.closeEntry();
            }
        }
        extraClasses = new Path[]{jar};
        Path cp = tmp.resolve("classpath-platform-errors.txt");
        List<String> lines = new ArrayList<>(Files.readAllLines(Path.of(CLASSPATH)));
        lines.add(jar.toString());
        Files.write(cp, lines);
        return cp;
    }

    /** #91: request validation, a ProblemDetail subclass without status, a blocked WebClient, a library's advice. */
    private static final IoAction LABEL_ERRORS = app -> {
        Path dir = app.resolve("src/main/java/com/example/e2e/label");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("LabelRequest.java"), """
                package com.example.e2e.label;

                import jakarta.validation.constraints.NotBlank;
                import jakarta.validation.constraints.Size;

                public record LabelRequest(@NotBlank String labelCode, @Size(max = 40) String text) {
                }
                """);
        // mapped by the library's advice (its base type): not a 500
        Files.writeString(dir.resolve("LabelMissing.java"), """
                package com.example.e2e.label;

                import org.orchardlib.errors.NotFound;

                public class LabelMissing extends NotFound {
                    public LabelMissing(String labelCode) {
                        super("no label " + labelCode);
                    }
                }
                """);
        for (String name : List.of("LabelStale", "LabelLocked")) {
            Files.writeString(dir.resolve(name + ".java"), """
                    package com.example.e2e.label;

                    public class %s extends RuntimeException {
                        public %s(String message) {
                            super(message);
                        }
                    }
                    """.formatted(name, name));
        }
        // ProblemDetail subclasses: one leaves the status 0, one passes it to ProblemDetail
        Files.writeString(dir.resolve("LabelProblem.java"), """
                package com.example.e2e.label;

                import org.springframework.http.ProblemDetail;

                public class LabelProblem extends ProblemDetail {
                    public LabelProblem(String detail) {
                        setDetail(detail);
                    }
                }
                """);
        Files.writeString(dir.resolve("LabelGone.java"), """
                package com.example.e2e.label;

                import org.springframework.http.ProblemDetail;

                public class LabelGone extends ProblemDetail {
                    public LabelGone() {
                        super(410);
                    }
                }
                """);
        // a catch-all that logs and answers 500 (validation failures land here first), two ProblemDetail handlers
        Files.writeString(dir.resolve("LabelErrors.java"), """
                package com.example.e2e.label;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.http.HttpStatus;
                import org.springframework.http.ProblemDetail;
                import org.springframework.web.bind.annotation.ExceptionHandler;
                import org.springframework.web.bind.annotation.RestControllerAdvice;

                @RestControllerAdvice
                class LabelErrors {
                    private static final Logger log = LoggerFactory.getLogger(LabelErrors.class);

                    @ExceptionHandler(Exception.class)
                    ProblemDetail unexpected(Exception e) {
                        log.error("Label request failed", e);
                        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
                    }

                    @ExceptionHandler(LabelStale.class)
                    ProblemDetail stale(LabelStale e) {
                        return new LabelProblem("stale label");
                    }

                    @ExceptionHandler(LabelLocked.class)
                    ProblemDetail locked(LabelLocked e) {
                        return new LabelGone();
                    }
                }
                """);
        // a WebClient call blocked on, on an HTTP path
        Files.writeString(dir.resolve("LabelStore.java"), """
                package com.example.e2e.label;

                import org.springframework.stereotype.Component;
                import org.springframework.web.reactive.function.client.WebClient;

                @Component
                class LabelStore {
                    private final WebClient client;

                    LabelStore(WebClient.Builder builder) {
                        this.client = builder.baseUrl("http://labels").build();
                    }

                    String find(String labelCode) {
                        return client.get().uri("/labels/{labelCode}", labelCode).retrieve().bodyToMono(String.class).block();
                    }
                }
                """);
        Files.writeString(dir.resolve("LabelController.java"), """
                package com.example.e2e.label;

                import jakarta.validation.Valid;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.PutMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                class LabelController {
                    private final LabelStore store;

                    LabelController(LabelStore store) {
                        this.store = store;
                    }

                    @PostMapping("/labels")
                    String create(@RequestBody LabelRequest request) {
                        return request.labelCode();
                    }

                    @PutMapping("/labels/{labelCode}")
                    String update(@PathVariable String labelCode, @Valid @RequestBody LabelRequest request) {
                        if (request.text() == null) {
                            throw new LabelStale(labelCode);
                        }
                        return labelCode;
                    }

                    @PostMapping("/labels/notes")
                    String note(@RequestBody String text) {
                        return text;
                    }

                    @GetMapping("/labels/{labelCode}")
                    String find(@PathVariable String labelCode) {
                        String label = store.find(labelCode);
                        if (label == null) {
                            throw new LabelMissing(labelCode);
                        }
                        if (label.isBlank()) {
                            throw new LabelLocked(labelCode);
                        }
                        return label;
                    }
                }
                """);
    };

    private static void assertLabelFindings(String findings) {
        assertThat(findings).as("a constrained body without @Valid")
                .contains("LabelController.create takes request (LabelRequest, with Bean Validation constraints) without @Valid")
                .as("with @Valid, or without constraints: not reported")
                .doesNotContain("LabelController.update takes").doesNotContain("LabelController.note takes");
        assertThat(findings).as("the catch-all answers the validation failures 500")
                .contains("Handler for Exception also takes the validation failures of 1 @Valid request body");
        assertThat(findings).as("a ProblemDetail subclass without a status")
                .contains("returns LabelProblem, a ProblemDetail built without a status")
                .doesNotContain("returns LabelGone");
        assertThat(findings).as("a blocked WebClient call nobody maps")
                .contains("Failures of downstream calls (WebClient, 1× on HTTP paths");
        assertThat(findings).as("mapped by the library's advice").doesNotContain("LabelMissing is thrown on HTTP paths");
    }

    /**
     * #91 / #52: a @RequestBody whose type has constraints but no @Valid is reported (REQUEST_NOT_VALIDATED), the @Valid
     * one and the unconstrained one are not; the catch-all answering 500 takes the @Valid body's failures before
     * Spring's 400 (CLIENT_ERROR_AS_SERVER_ERROR); a handler returning a ProblemDetail subclass whose constructor sets no
     * status is reported, the one passing 410 is not (ERROR_HANDLER_STATUS_OK); a WebClient call blocked on, on an HTTP
     * path, is a downstream failure nobody maps (UNMAPPED_EXCEPTION); an exception whose base type a library's
     * @RestControllerAdvice maps is not reported. The same with only the errors pack. Every file must equal
     * e2e/expected-errors-pack-validation.
     */
    @Test
    void errorsPackFindsUnvalidatedBodiesStatuslessProblemsBlockedWebClientsAndLibraryHandlers() throws IOException {
        String classpath = platformErrorsClasspath().toString();
        Map<String, String> actual = migrate(tmp.resolve("app"), classpath, LABEL_ERRORS);
        assertLabelFindings(actual.get("FINDINGS.txt"));
        golden(EXPECTED.resolveSibling("expected-errors-pack-validation"), actual);

        Map<String, String> alone = migrate(tmp.resolve("errors-only"), classpath, app -> {
            LABEL_ERRORS.run(app);
            Path yml = app.resolve("logast.yml");
            Files.writeString(yml, Files.readString(yml).replaceFirst("(?m)^packs: .*$", "packs: [errors]"));
        });
        assertLabelFindings(alone.get("FINDINGS.txt"));
    }

    /** #89: the shapes of the tracing pack's newer checks, in a stock service. */
    private static final IoAction STOCK_TRACING = app -> {
        Path dir = app.resolve("src/main/java/com/example/e2e/stock");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("StockPicked.java"), """
                package com.example.e2e.stock;

                public record StockPicked(String stockCode) {
                }
                """);
        // a startup listener (outside any trace) and a service method observed by annotation only (inactive: no property, no aspectjweaver)
        Files.writeString(dir.resolve("StockTracker.java"), """
                package com.example.e2e.stock;

                import io.micrometer.observation.annotation.Observed;
                import io.micrometer.tracing.annotation.NewSpan;
                import java.util.HashSet;
                import java.util.Set;
                import org.springframework.boot.context.event.ApplicationReadyEvent;
                import org.springframework.context.event.EventListener;
                import org.springframework.stereotype.Component;

                @Component
                class StockTracker {
                    private final Set<String> picked = new HashSet<>();

                    @EventListener(ApplicationReadyEvent.class)
                    @Observed(name = "stock.warm-up")
                    void warmUp() {
                        picked.clear();
                    }

                    @EventListener
                    void onPicked(StockPicked event) {
                        picked.add(event.stockCode());
                    }

                    @NewSpan("stock.count")
                    int count() {
                        return picked.size();
                    }
                }
                """);
        // spans and observations started by hand: left open, no error, no scope; one done right; one with observe()
        Files.writeString(dir.resolve("StockRouting.java"), """
                package com.example.e2e.stock;

                import io.micrometer.observation.Observation;
                import io.micrometer.observation.ObservationRegistry;
                import io.micrometer.tracing.Span;
                import io.micrometer.tracing.Tracer;
                import org.springframework.stereotype.Component;

                @Component
                class StockRouting {
                    private final ObservationRegistry registry;
                    private final Tracer tracer;

                    StockRouting(ObservationRegistry registry, Tracer tracer) {
                        this.registry = registry;
                        this.tracer = tracer;
                    }

                    String route(String stockCode) {
                        Observation observation = Observation.start("stock.route", registry);
                        String lane = "lane-" + stockCode;
                        observation.stop();
                        return lane;
                    }

                    String weigh(String stockCode) {
                        Observation observation = Observation.createNotStarted("stock.weigh", registry).start();
                        try (Observation.Scope scope = observation.openScope()) {
                            return "weight-" + stockCode;
                        } finally {
                            observation.stop();
                        }
                    }

                    String plan(String stockCode) {
                        Span span = tracer.nextSpan().name("stock.plan").start();
                        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
                            return "plan-" + stockCode;
                        } catch (RuntimeException e) {
                            span.error(e);
                            throw e;
                        } finally {
                            span.end();
                        }
                    }

                    String label(String stockCode) {
                        return Observation.createNotStarted("stock.label", registry).observe(() -> "label-" + stockCode);
                    }
                }
                """);
    };

    /**
     * #89: the tracing pack's newer checks. {@code @Observed} / {@code @NewSpan} without
     * management.observations.annotations.enabled and aspectjweaver do nothing: the property is set and the AspectJ
     * starter added (OBSERVED_ANNOTATION_INACTIVE, AUTO), so the annotated event listener is not wrapped in an
     * ObservedContext too. Observations and spans started by hand without stop() in finally, error() or a scope are
     * reported; the one done right and observe() are not (SPAN_NOT_CLOSED). No OTLP endpoint is set anywhere, so
     * traces are exported nowhere (TRACING_EXPORT_UNCONFIGURED). The same checks with only the tracing pack selected.
     * Every file must equal e2e/expected-tracing-pack.
     */
    @Test
    void tracingPackActivatesObservedAnnotationsAndFindsOpenSpansAndMissingExport() throws IOException {
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, STOCK_TRACING);
        assertThat(actual.get("src/main/resources/application.yml")).as("annotations switched on")
                .contains("observations:\n    annotations:\n      enabled: true");
        assertThat(actual.get("build.gradle.kts")).as("aspectjweaver through the Boot 4 starter")
                .contains("implementation(\"org.springframework.boot:spring-boot-starter-aspectj\")");
        assertThat(actual.get("src/main/java/com/example/e2e/stock/StockTracker.java")).as("observed by its annotation now")
                .doesNotContain("ObservedContext");
        String findings = actual.get("FINDINGS.txt");
        assertThat(findings).as("left open, no error, no scope")
                .contains("Observation observation in StockRouting.route is started by hand: stop() is not in a finally block");
        assertThat(findings).as("stopped in finally and scoped, but no error recorded")
                .contains("Observation observation in StockRouting.weigh is started by hand: no failure is recorded");
        assertThat(findings).as("done right, or observe()").doesNotContain("StockRouting.plan").doesNotContain("StockRouting.label");
        assertThat(findings).as("no endpoint").contains("Traces are exported nowhere");
        golden(EXPECTED.resolveSibling("expected-tracing-pack"), actual);

        // #96: logast's own output kept in the tree names the endpoint variables in its suggestion; it is no configuration,
        // so a rescan still reports the missing export (FINDINGS.txt / OUTPUT.txt from above, a saved JSON and SARIF scan)
        Path saved = tmp.resolve("app");
        Files.writeString(saved.resolve("scan.json"), run(saved, "scan", saved.toString(), "--classpath-file", CLASSPATH, "--format", "json"));
        Files.writeString(saved.resolve("logast.sarif"), run(saved, "scan", saved.toString(), "--classpath-file", CLASSPATH, "--format", "sarif"));
        assertThat(Files.readString(saved.resolve("FINDINGS.txt"))).contains("OTEL_EXPORTER_OTLP_ENDPOINT");
        assertThat(run(saved, "scan", saved.toString(), "--classpath-file", CLASSPATH)).as("rescan with the saved output in the tree")
                .contains("Traces are exported nowhere");

        // the tracing pack alone: the same checks
        Map<String, String> alone = migrate(tmp.resolve("tracing-only"), CLASSPATH, app -> {
            STOCK_TRACING.run(app);
            Path yml = app.resolve("logast.yml");
            Files.writeString(yml, Files.readString(yml).replaceFirst("(?m)^packs: .*$", "packs: [tracing]"));
        });
        assertThat(alone.get("src/main/resources/application.yml")).contains("observations:\n    annotations:\n      enabled: true");
        assertThat(alone.get("build.gradle.kts")).contains("spring-boot-starter-aspectj");
        assertThat(alone.get("src/main/java/com/example/e2e/stock/StockTracker.java")).doesNotContain("ObservedContext");
        assertThat(alone.get("FINDINGS.txt"))
                .contains("Observation observation in StockRouting.route is started by hand")
                .contains("Observation observation in StockRouting.weigh is started by hand")
                .doesNotContain("StockRouting.plan")
                .contains("Traces are exported nowhere");

        // with the annotations left inactive (the rule switched off), @Observed no longer counts as observing: the
        // listener gets its ObservedContext
        Map<String, String> inactive = migrate(tmp.resolve("annotations-inactive"), CLASSPATH, app -> {
            STOCK_TRACING.run(app);
            Path yml = app.resolve("logast.yml");
            Files.writeString(yml, Files.readString(yml).replaceFirst("(?m)^packs: .*$", "packs: [tracing]")
                    + "rules:\n  OBSERVED_ANNOTATION_INACTIVE:\n    enabled: false\n");
        });
        assertThat(inactive.get("build.gradle.kts")).doesNotContain("spring-boot-starter-aspectj");
        assertThat(inactive.get("src/main/java/com/example/e2e/stock/StockTracker.java"))
                .contains("ObservedContext.entrypoint(\"stock-tracker.warm-up\")");
    }

    /** #89: observation and span names built from ids, ids as low-cardinality key values, a log pattern without the trace id. */
    private static final IoAction DISPATCH_TRACING = app -> {
        Path dir = app.resolve("src/main/java/com/example/e2e/dispatch");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("DispatchPlanner.java"), """
                package com.example.e2e.dispatch;

                import io.micrometer.observation.Observation;
                import io.micrometer.observation.ObservationRegistry;
                import io.micrometer.observation.annotation.Observed;
                import io.micrometer.tracing.Span;
                import io.micrometer.tracing.Tracer;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.stereotype.Component;

                @Component
                public class DispatchPlanner {
                    private static final Logger log = LoggerFactory.getLogger(DispatchPlanner.class);
                    private final ObservationRegistry registry;
                    private final Tracer tracer;

                    DispatchPlanner(ObservationRegistry registry, Tracer tracer) {
                        this.registry = registry;
                        this.tracer = tracer;
                    }

                    @Observed(name = "dispatch.plan")
                    public void plan() {
                        log.info("Dispatch planning started");
                        log.info("Dispatch planning completed");
                    }

                    public String route(String dispatchId, String lane) {
                        return Observation.createNotStarted("dispatch.route." + dispatchId, registry)
                                .lowCardinalityKeyValue("dispatch.lane", lane)
                                .observe(() -> lane + "-" + dispatchId);
                    }

                    public String load(String orderId, String lane) {
                        return Observation.createNotStarted("dispatch.load", registry)
                                .highCardinalityKeyValue("dispatch.lane", lane)
                                .lowCardinalityKeyValue("order", orderId)
                                .observe(() -> "load-" + orderId);
                    }

                    public String pick(String dispatchId) {
                        Span span = tracer.nextSpan().name("dispatch.pick-%s".formatted(dispatchId)).start();
                        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
                            return "pick-" + dispatchId;
                        } catch (RuntimeException e) {
                            span.error(e);
                            throw e;
                        } finally {
                            span.end();
                        }
                    }

                    public String weigh(String dispatchId, String kind) {
                        return Observation.createNotStarted("dispatch.weigh." + kind, registry)
                                .highCardinalityKeyValue("dispatch.id", dispatchId)
                                .observe(() -> "weight-" + dispatchId);
                    }
                }
                """);
        // the console pattern drops the trace id; the file pattern keeps it
        Files.writeString(app.resolve("src/main/resources/logback-spring.xml"), """
                <configuration>
                    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
                        <encoder>
                            <pattern>%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n</pattern>
                        </encoder>
                    </appender>
                    <appender name="FILE" class="ch.qos.logback.core.FileAppender">
                        <file>dispatch.log</file>
                        <encoder>
                            <pattern>%d [%X{traceId:-},%X{spanId:-}] %-5level %logger - %msg%n</pattern>
                        </encoder>
                    </appender>
                    <root level="INFO">
                        <appender-ref ref="CONSOLE"/>
                        <appender-ref ref="FILE"/>
                    </root>
                </configuration>
                """);
    };

    private static IoAction withPacks(IoAction prepare, String packs, String rules) {
        return app -> {
            prepare.run(app);
            Path yml = app.resolve("logast.yml");
            Files.writeString(yml, Files.readString(yml).replaceFirst("(?m)^packs: .*$", "packs: " + packs) + rules);
        };
    }

    /**
     * #19: opting in to more spans is {@code @Observed} on the method. A service method below a controller and an
     * {@code @EventListener} running inside the publisher's flow, each {@code @Observed(name = ...)}; a listener for an
     * event published outside the sources with it, and one without it (which gets its business timer, as before).
     */
    private static final IoAction BERTH_OPT_IN_SPANS = app -> {
        Path dir = app.resolve("src/main/java/com/example/e2e/berth");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("BerthReleased.java"), """
                package com.example.e2e.berth;

                public record BerthReleased(String berthCode) {
                }
                """);
        // published by code outside this service's sources (a message bridge): its listeners run in no observed flow
        Files.writeString(dir.resolve("BerthInspected.java"), """
                package com.example.e2e.berth;

                public record BerthInspected(String berthCode) {
                }
                """);
        Files.writeString(dir.resolve("BerthController.java"), """
                package com.example.e2e.berth;

                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                class BerthController {
                    private final BerthService service;

                    BerthController(BerthService service) {
                        this.service = service;
                    }

                    @PostMapping("/berths/{berthCode}/release")
                    void release(@PathVariable String berthCode) {
                        service.release(berthCode);
                    }

                    @PostMapping("/berths/{berthCode}/assign")
                    void assign(@PathVariable String berthCode) {
                        service.assign(berthCode);
                    }
                }
                """);
        Files.writeString(dir.resolve("BerthService.java"), """
                package com.example.e2e.berth;

                import io.micrometer.observation.annotation.Observed;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.context.ApplicationEventPublisher;
                import org.springframework.stereotype.Service;

                @Service
                class BerthService {
                    private static final Logger log = LoggerFactory.getLogger(BerthService.class);
                    private final ApplicationEventPublisher events;

                    BerthService(ApplicationEventPublisher events) {
                        this.events = events;
                    }

                    @Observed(name = "berth.release")
                    public void release(String berthCode) {
                        log.info("Berth released");
                        events.publishEvent(new BerthReleased(berthCode));
                    }

                    public void assign(String berthCode) {
                        log.info("Berth assigned");
                    }
                }
                """);
        Files.writeString(dir.resolve("BerthLedger.java"), """
                package com.example.e2e.berth;

                import io.micrometer.observation.annotation.Observed;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.context.event.EventListener;
                import org.springframework.stereotype.Component;

                @Component
                class BerthLedger {
                    private static final Logger log = LoggerFactory.getLogger(BerthLedger.class);

                    @EventListener
                    @Observed(name = "berth.ledger-entry")
                    public void onReleased(BerthReleased event) {
                        log.info("Ledger sync started");
                        log.info("Ledger sync completed");
                    }

                    @EventListener
                    @Observed(name = "berth.inspection")
                    public void onInspected(BerthInspected event) {
                        log.info("Inspection sync started");
                        log.info("Inspection sync completed");
                    }

                    @EventListener
                    public void onInspectedNotify(BerthInspected event) {
                        log.info("Notice sync started");
                        log.info("Notice sync completed");
                    }
                }
                """);
    };

    /**
     * #19: per-method spans are opted in with {@code @Observed(name = ...)}, nothing new. logast makes Spring Boot process
     * the annotation (OBSERVED_ANNOTATION_INACTIVE: property and AspectJ starter), and then counts the annotated service
     * method and event listener as observed: no ObservedContext around them (ENTRYPOINT_NOT_OBSERVED, alreadyObserved) and
     * no second business timer (BUSINESS_EVENT_UNMEASURED), and no counter for the "completed" its observation already
     * counts (METRIC_OUTCOME_UNMEASURED missed the annotation and added one); a constant name is no dynamic span name
     * (SPAN_NAME_DYNAMIC).
     * The same with the tracing pack alone. Every file must equal e2e/expected-opt-in-spans.
     */
    @Test
    void observedOptsMethodsInToSpansWithoutASecondObservation() throws IOException {
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, BERTH_OPT_IN_SPANS);
        assertThat(actual.get("src/main/resources/application.yml")).as("annotations switched on")
                .contains("observations:\n    annotations:\n      enabled: true");
        assertThat(actual.get("build.gradle.kts")).contains("implementation(\"org.springframework.boot:spring-boot-starter-aspectj\")");
        String service = actual.get("src/main/java/com/example/e2e/berth/BerthService.java");
        String ledger = actual.get("src/main/java/com/example/e2e/berth/BerthLedger.java");
        assertThat(between(service, "public void release", "public void assign")).as("@Observed method: no second observation")
                .doesNotContain("ObservedContext");
        assertThat(between(ledger, "public void onReleased(", "public void onInspectedNotify")).as("@Observed listeners: no second observation")
                .doesNotContain("ObservedContext")
                .as("nor a counter for the completion their observation counts").doesNotContain("BerthMetrics.");
        assertThat(ledger.substring(ledger.indexOf("public void onInspectedNotify"))).as("the listener without it gets its business timer")
                .contains("ObservedContext.entrypoint(");
        String findings = actual.get("FINDINGS.txt");
        assertThat(findings).as("constant names are not dynamic").doesNotContainPattern("SPAN_NAME_DYNAMIC .*/berth/");
        assertThat(findings).as("their observation counts the completion: no outcome gap")
                .doesNotContain("BerthLedger.onReleased\n").doesNotContain("BerthLedger.onInspected\n");
        golden(EXPECTED.resolveSibling("expected-opt-in-spans"), actual);

        Map<String, String> alone = migrate(tmp.resolve("tracing-only"), CLASSPATH, withPacks(BERTH_OPT_IN_SPANS, "[tracing]", ""));
        assertThat(alone.get("src/main/resources/application.yml")).contains("observations:\n    annotations:\n      enabled: true");
        assertThat(alone.get("build.gradle.kts")).contains("spring-boot-starter-aspectj");
        assertThat(between(alone.get("src/main/java/com/example/e2e/berth/BerthService.java"), "public void release", "public void assign"))
                .doesNotContain("ObservedContext");
        assertThat(between(alone.get("src/main/java/com/example/e2e/berth/BerthLedger.java"), "public void onReleased(", "public void onInspectedNotify"))
                .doesNotContain("ObservedContext");
        assertThat(alone.get("FINDINGS.txt")).doesNotContainPattern("SPAN_NAME_DYNAMIC .*/berth/");
    }

    /** #16: code of the service that hands a business id to the next service as baggage (written by a person, never by logast). */
    private static final IoAction TENANT_BAGGAGE = app -> {
        Path dir = app.resolve("src/main/java/com/example/e2e/tenant");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("TenantRelay.java"), """
                package com.example.e2e.tenant;

                import com.example.e2e.logging.BusinessBaggage;
                import org.springframework.stereotype.Component;

                @Component
                public class TenantRelay {
                    public void relay(String tenantId, Runnable downstreamCall) {
                        try (var baggage = BusinessBaggage.with("tenantId", tenantId)) {
                            downstreamCall.run();
                        }
                    }
                }
                """);
    };

    /**
     * #16: BusinessBaggage is copied in vendor mode only once the service's code uses it, with its Tracer wiring
     * (Micrometer Tracing is on the classpath): the result compiles. A service that does not use it gets neither.
     * A service vendored before it used the helper gets it on the next apply. Every file must equal
     * e2e/expected-business-baggage.
     */
    @Test
    void businessBaggageIsCopiedInVendorModeOnlyWhenUsed() throws IOException {
        Path used = tmp.resolve("app");
        Map<String, String> actual = migrate(used, CLASSPATH, TENANT_BAGGAGE);
        // the copied support classes are compiled (migrate does), not part of the snapshot
        Path support = used.resolve("src/main/java/com/example/e2e/logging");
        assertThat(support.resolve("BusinessBaggage.java")).exists();
        assertThat(Files.readString(support.resolve("BusinessBaggageConfiguration.java")))
                .contains("@Configuration(proxyBeanMethods = false)").doesNotContain("@AutoConfiguration");
        golden(EXPECTED.resolveSibling("expected-business-baggage"), actual);

        // vendored first, the helper used later: the next apply adds it
        Path later = tmp.resolve("later");
        migrate(later, CLASSPATH, app -> {
        });
        assertThat(later.resolve("src/main/java/com/example/e2e/logging/BusinessBaggage.java")).doesNotExist();
        TENANT_BAGGAGE.run(later);
        String out = run(later, "apply", later.toString(), "--auto", "--write", "--classpath-file", CLASSPATH);
        assertThat(out).doesNotContain("CHECK FAILED");
        assertThat(later.resolve("src/main/java/com/example/e2e/logging/BusinessBaggage.java")).exists();
        assertThat(later.resolve("src/main/java/com/example/e2e/logging/BusinessBaggageConfiguration.java")).exists();
        compile(later);
    }

    private static String between(String text, String from, String to) {
        int start = text.indexOf(from);
        int end = text.indexOf(to, start);
        assertThat(start).as(from).isNotNegative();
        assertThat(end).as(to).isPositive();
        return text.substring(start, end);
    }

    /**
     * #89: the tracing pack alone (snapshot e2e/expected-tracing-depth). An observation or span name built from an id,
     * and an id as a low-cardinality key value, give one timer series and span name per value (SPAN_NAME_DYNAMIC); a name
     * from a bounded value and an id as a high-cardinality key value do not. The console pattern of logback-spring.xml has
     * no trace id (TRACE_ID_NOT_LOGGED); the file pattern has. Also: with every pack, SETUP_LOGBACK_XML covers the file
     * instead; with the metrics pack alone, the inactive {@code @Observed} measures nothing, so the method's business
     * event gets its observation (BUSINESS_EVENT_UNMEASURED).
     */
    @Test
    void tracingPackFindsIdsInSpanNamesAndLogPatternsWithoutTheTraceId() throws IOException {
        Path tracingOnly = tmp.resolve("tracing-only");
        Map<String, String> alone = migrate(tracingOnly, CLASSPATH, withPacks(DISPATCH_TRACING, "[tracing]", ""));
        String findings = alone.get("FINDINGS.txt");
        assertThat(findings).as("name built from an id")
                .contains("Observation name built from dispatchId in DispatchPlanner.route")
                .contains("Span name built from dispatchId in DispatchPlanner.pick");
        assertThat(findings).as("an id (vocabulary key) as a low-cardinality key value")
                .contains("Low-cardinality key value 'order' is orderId in DispatchPlanner.load");
        assertThat(findings).as("bounded name, bounded key value, id as high-cardinality key value")
                .doesNotContain("DispatchPlanner.weigh").doesNotContain("'dispatch.lane'");
        assertThat(findings).as("console pattern without the trace id")
                .contains("logback-spring.xml logs with a pattern of its own without the trace id");
        golden(EXPECTED.resolveSibling("expected-tracing-depth"), alone);

        // both rules switched off: their findings go, nothing else changes (knock-out)
        Path yml = tracingOnly.resolve("logast.yml");
        Files.writeString(yml, Files.readString(yml) + "rules:\n  SPAN_NAME_DYNAMIC:\n    enabled: false\n"
                + "  TRACE_ID_NOT_LOGGED:\n    enabled: false\n");
        String off = run(tracingOnly, "scan", tracingOnly.toString(), "--classpath-file", CLASSPATH);
        assertThat(off).doesNotContain("SPAN_NAME_DYNAMIC").doesNotContain("TRACE_ID_NOT_LOGGED")
                .contains("ASYNC_CONTEXT_LOSS");

        // every pack: the logging pack's SETUP_LOGBACK_XML replaces the file, so no second finding for it
        Map<String, String> all = migrate(tmp.resolve("all"), CLASSPATH, DISPATCH_TRACING);
        assertThat(all.get("FINDINGS.txt")).contains("Observation name built from dispatchId in DispatchPlanner.route")
                .doesNotContain("TRACE_ID_NOT_LOGGED");
        assertThat(all.get("src/main/java/com/example/e2e/dispatch/DispatchPlanner.java")).as("@Observed switched on: measured")
                .doesNotContain("ObservedContext");

        // the metrics pack alone with the annotations left inactive: @Observed measures nothing, the business event gets
        // its observation (the metrics pack switches them on by itself: metricsPackSwitchesOnTimedAndCountedOnItsOwn)
        Map<String, String> metrics = migrate(tmp.resolve("metrics-only"), CLASSPATH, withPacks(DISPATCH_TRACING, "[metrics]",
                "rules:\n  OBSERVED_ANNOTATION_INACTIVE:\n    enabled: false\n"));
        assertThat(metrics.get("src/main/java/com/example/e2e/dispatch/DispatchPlanner.java"))
                .contains("ObservedContext.entrypoint(DispatchMetrics.DISPATCH_PLANNING)");
    }

    /** #17: an SSE departure feed broadcasting to the emitters of its subscribers, and a STOMP inbound interceptor. */
    private static final IoAction DEPARTURE_FEED = app -> {
        Path dir = app.resolve("src/main/java/com/example/e2e/departure");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("DepartureFeed.java"), """
                package com.example.e2e.departure;

                import java.io.IOException;
                import java.util.Map;
                import java.util.concurrent.ConcurrentHashMap;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.messaging.simp.SimpMessagingTemplate;
                import org.springframework.stereotype.Component;
                import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

                @Component
                public class DepartureFeed {
                    private static final Logger log = LoggerFactory.getLogger(DepartureFeed.class);
                    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
                    private final SimpMessagingTemplate messaging;

                    public DepartureFeed(SimpMessagingTemplate messaging) {
                        this.messaging = messaging;
                    }

                    public SseEmitter subscribe(String subscriberId) {
                        SseEmitter emitter = new SseEmitter(0L);
                        emitters.put(subscriberId, emitter);
                        emitter.onCompletion(() -> emitters.remove(subscriberId));
                        return emitter;
                    }

                    public void publish(String departure) {
                        emitters.forEach((subscriberId, emitter) -> {
                            try {
                                emitter.send(departure);
                            } catch (IOException e) {
                                log.warn("Departure push failed", e);
                                emitters.remove(subscriberId);
                            }
                        });
                        messaging.convertAndSend("/topic/departures", departure);
                    }

                    public void delay(String departure) {
                        for (Map.Entry<String, SseEmitter> entry : emitters.entrySet()) {
                            try {
                                entry.getValue().send(departure);
                            } catch (IOException e) {
                                log.warn("Delay push to subscriber {} failed", entry.getKey(), e);
                            }
                        }
                    }

                    public void cancel(String departure) {
                        for (SseEmitter emitter : emitters.values()) {
                            try {
                                emitter.send(departure);
                            } catch (IOException e) {
                                log.warn("Cancellation push failed for {}", emitter, e);
                            }
                        }
                    }

                    public void heartbeat() throws IOException {
                        for (SseEmitter emitter : emitters.values()) {
                            emitter.send(SseEmitter.event().comment("ping"));
                        }
                    }
                }
                """);
        Files.writeString(dir.resolve("DepartureController.java"), """
                package com.example.e2e.departure;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RestController;
                import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

                @RestController
                public class DepartureController {
                    private final DepartureFeed feed;

                    public DepartureController(DepartureFeed feed) {
                        this.feed = feed;
                    }

                    @GetMapping("/departures/{subscriberId}/stream")
                    public SseEmitter stream(@PathVariable String subscriberId) {
                        return feed.subscribe(subscriberId);
                    }

                    @PostMapping("/departures")
                    public void announce(@RequestBody String departure) {
                        feed.publish(departure);
                    }

                    @PostMapping("/departures/delays")
                    public void delay(@RequestBody String departure) {
                        feed.delay(departure);
                    }

                    @PostMapping("/departures/cancellations")
                    public void cancel(@RequestBody String departure) {
                        feed.cancel(departure);
                    }
                }
                """);
        Files.writeString(dir.resolve("DepartureSocketConfig.java"), """
                package com.example.e2e.departure;

                import org.springframework.context.annotation.Configuration;
                import org.springframework.messaging.simp.config.ChannelRegistration;
                import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
                import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

                @Configuration
                @EnableWebSocketMessageBroker
                public class DepartureSocketConfig implements WebSocketMessageBrokerConfigurer {
                    @Override
                    public void configureClientInboundChannel(ChannelRegistration registration) {
                        registration.interceptors(new StationInterceptor());
                    }
                }
                """);
        Files.writeString(dir.resolve("StationInterceptor.java"), """
                package com.example.e2e.departure;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.messaging.Message;
                import org.springframework.messaging.MessageChannel;
                import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
                import org.springframework.messaging.support.ChannelInterceptor;

                /** Runs on the WebSocket session's thread before the inbound channel hands the frame to its executor. */
                public class StationInterceptor implements ChannelInterceptor {
                    private static final Logger log = LoggerFactory.getLogger(StationInterceptor.class);

                    @Override
                    public Message<?> preSend(Message<?> message, MessageChannel channel) {
                        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
                        log.debug("Inbound frame {} for {}", accessor.getCommand(), accessor.getDestination());
                        return message;
                    }
                }
                """);
    };

    private static final String DEPARTURE = "src/main/java/com/example/e2e/departure/";

    /**
     * #17: {@code SseEmitter.send} writes on the calling thread, so a broadcast to the emitters of every subscriber logs
     * with the sender's MDC and trace (no executor hop the TaskDecorator could carry). A line in such a loop that does not
     * name the subscriber cannot be matched to it (SSE_BROADCAST_CONTEXT): the forEach over the map (publish) and the loop
     * over its values (cancel; logging the emitter does not name anyone) are reported; the loop over the entries that logs
     * the key (delay) and the loop that logs nothing (heartbeat) are not. The onCompletion callback registered at subscribe
     * gets the subscriber's context (ASYNC_CONTEXT_LOSS, as before). STOMP: the inbound ChannelInterceptor's preSend and
     * the SimpMessagingTemplate broadcast are left alone (preSend runs on the sending thread before any hop; Boot routes
     * the channels' executors through applicationTaskExecutor, which the TaskDecorator decorates). Every file must equal
     * e2e/expected-sse-broadcast; the tracing pack alone reports the same; switched off, the findings go.
     */
    @Test
    void reportsBroadcastLinesThatDoNotNameTheSubscriber() throws IOException {
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, DEPARTURE_FEED);
        String findings = actual.get("FINDINGS.txt");
        assertThat(findings).as("forEach over the map and loop over its values: per-subscriber lines without the subscriber")
                .contains("SseEmitter.send to every subscriber in DepartureFeed.publish")
                .contains("SseEmitter.send to every subscriber in DepartureFeed.cancel")
                .as("the key logged, or nothing logged").doesNotContain("subscriber in DepartureFeed.delay")
                .doesNotContain("subscriber in DepartureFeed.heartbeat")
                .as("STOMP preSend runs before any hop: nothing to carry")
                .doesNotContainPattern("(SSE_BROADCAST_CONTEXT|ASYNC_CONTEXT_LOSS)\\s+\\S+StationInterceptor");
        assertThat(actual.get(DEPARTURE + "DepartureFeed.java")).as("the subscriber's callback carries its context")
                .contains("emitter.onCompletion(ContextPropagation.wrapRunnable(() -> emitters.remove(subscriberId)))");
        golden(EXPECTED.resolveSibling("expected-sse-broadcast"), actual);

        // the tracing pack alone reports the same broadcasts
        Path tracingOnly = tmp.resolve("tracing-only");
        Map<String, String> tracing = migrate(tracingOnly, CLASSPATH, withPacks(DEPARTURE_FEED, "[tracing]", ""));
        assertThat(tracing.get("FINDINGS.txt")).contains("SseEmitter.send to every subscriber in DepartureFeed.publish")
                .contains("SseEmitter.send to every subscriber in DepartureFeed.cancel");

        // switched off: its findings go (knock-out)
        Path yml = tracingOnly.resolve("logast.yml");
        Files.writeString(yml, Files.readString(yml) + "rules:\n  SSE_BROADCAST_CONTEXT:\n    enabled: false\n");
        assertThat(run(tracingOnly, "scan", tracingOnly.toString(), "--classpath-file", CLASSPATH))
                .doesNotContain("SSE_BROADCAST_CONTEXT").doesNotContain("SseEmitter.send to every subscriber");
    }

    /** #95: business events measured only by {@code @Timed} / {@code @Counted}. */
    private static final IoAction SHELF_METERS = app -> {
        Path dir = app.resolve("src/main/java/com/example/e2e/shelf");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("ShelfKeeper.java"), """
                package com.example.e2e.shelf;

                import io.micrometer.core.annotation.Counted;
                import io.micrometer.core.annotation.Timed;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.stereotype.Component;

                @Component
                public class ShelfKeeper {
                    private static final Logger log = LoggerFactory.getLogger(ShelfKeeper.class);

                    @Timed("shelf.restock")
                    public void restock(String shelfCode) {
                        log.info("Shelf restock started");
                        log.info("Shelf restock completed");
                    }

                    @Counted("shelf.inspection")
                    public void inspect(String shelfCode) {
                        log.info("Shelf inspection started");
                        log.info("Shelf inspection completed");
                    }
                }
                """);
    };

    /**
     * #95: {@code @Timed} / {@code @Counted} need the same switch as {@code @Observed}
     * (management.observations.annotations.enabled and aspectjweaver: MetricsAspectsAutoConfiguration, Boot 3.5 / 4.1).
     * The metrics pack alone switches them on (OBSERVED_ANNOTATION_INACTIVE belongs to both packs), so the annotated
     * methods count as measured and get no observation of their own. Every file must equal
     * e2e/expected-metrics-annotations. With the rule switched off they stay inactive and measure nothing: the business
     * events get their observation (BUSINESS_EVENT_UNMEASURED). With both packs, one order switches them on.
     */
    @Test
    void metricsPackSwitchesOnTimedAndCountedOnItsOwn() throws IOException {
        Path before = tmp.resolve("scan-first");
        copy(BEFORE, before);
        withPacks(SHELF_METERS, "[metrics]", "").run(before);
        assertThat(run(before, "scan", before.toString(), "--classpath-file", CLASSPATH)).as("what the order says")
                .contains("@Timed / @Counted do nothing without management.observations.annotations.enabled: true and "
                        + "aspectjweaver: the annotated methods run without a timer or counter");
        Map<String, String> actual = migrate(tmp.resolve("metrics-only"), CLASSPATH, withPacks(SHELF_METERS, "[metrics]", ""));
        assertThat(actual.get("src/main/resources/application.yml")).as("annotations switched on by the metrics pack")
                .contains("observations:\n    annotations:\n      enabled: true");
        assertThat(actual.get("build.gradle.kts")).as("aspectjweaver through the Boot 4 starter")
                .contains("implementation(\"org.springframework.boot:spring-boot-starter-aspectj\")");
        assertThat(actual.get("src/main/java/com/example/e2e/shelf/ShelfKeeper.java")).as("measured by @Timed / @Counted")
                .doesNotContain("ObservedContext");
        assertThat(actual.get("OUTPUT.txt")).contains("OBA-");
        golden(EXPECTED.resolveSibling("expected-metrics-annotations"), actual);

        // left inactive: the annotations measure nothing
        Map<String, String> inactive = migrate(tmp.resolve("inactive"), CLASSPATH, withPacks(SHELF_METERS, "[metrics]",
                "rules:\n  OBSERVED_ANNOTATION_INACTIVE:\n    enabled: false\n"));
        assertThat(inactive.get("build.gradle.kts")).doesNotContain("spring-boot-starter-aspectj");
        assertThat(inactive.get("src/main/java/com/example/e2e/shelf/ShelfKeeper.java"))
                .contains("ObservedContext.entrypoint(ShelfMetrics.SHELF_RESTOCK)")
                .contains("ObservedContext.entrypoint(ShelfMetrics.SHELF_INSPECTION)");

        // both packs: one order switches them on, nothing is edited twice
        Map<String, String> both = migrate(tmp.resolve("both"), CLASSPATH, withPacks(SHELF_METERS, "[tracing, metrics]", ""));
        assertThat(both.get("src/main/resources/application.yml").split("annotations:", -1)).hasSize(2);
        assertThat(both.get("build.gradle.kts").split("spring-boot-starter-aspectj", -1)).hasSize(2);
        assertThat(both.get("src/main/java/com/example/e2e/shelf/ShelfKeeper.java")).doesNotContain("ObservedContext");
    }

    /**
     * Stand-ins for Spring Batch 5, Spring Cloud AWS SQS and Spring Cloud Stream, which are not on the e2e classpath
     * (adding them would change every scenario): only the types the entrypoint scenario uses (#15).
     */
    private static final Map<String, String> ENTRYPOINT_LIBRARY_STUBS = Map.of(
            "org/springframework/batch/core/step/tasklet/Tasklet.java", """
                    package org.springframework.batch.core.step.tasklet;

                    public interface Tasklet {
                        org.springframework.batch.repeat.RepeatStatus execute(org.springframework.batch.core.StepContribution contribution,
                                org.springframework.batch.core.scope.context.ChunkContext chunkContext) throws Exception;
                    }
                    """,
            "org/springframework/batch/core/StepContribution.java", """
                    package org.springframework.batch.core;

                    public class StepContribution {
                    }
                    """,
            "org/springframework/batch/core/scope/context/ChunkContext.java", """
                    package org.springframework.batch.core.scope.context;

                    public class ChunkContext {
                    }
                    """,
            "org/springframework/batch/repeat/RepeatStatus.java", """
                    package org.springframework.batch.repeat;

                    public enum RepeatStatus { CONTINUABLE, FINISHED }
                    """,
            "org/springframework/batch/item/ItemProcessor.java", """
                    package org.springframework.batch.item;

                    public interface ItemProcessor<I, O> {
                        O process(I item) throws Exception;
                    }
                    """,
            "org/springframework/batch/item/ItemWriter.java", """
                    package org.springframework.batch.item;

                    public interface ItemWriter<T> {
                        void write(Chunk<? extends T> chunk) throws Exception;
                    }
                    """,
            "org/springframework/batch/item/Chunk.java", """
                    package org.springframework.batch.item;

                    public class Chunk<T> implements Iterable<T> {
                        private final java.util.List<T> items = new java.util.ArrayList<>();

                        public java.util.Iterator<T> iterator() {
                            return items.iterator();
                        }

                        public int size() {
                            return items.size();
                        }
                    }
                    """,
            "org/springframework/batch/core/configuration/annotation/EnableBatchProcessing.java", """
                    package org.springframework.batch.core.configuration.annotation;

                    import java.lang.annotation.Retention;
                    import java.lang.annotation.RetentionPolicy;

                    @Retention(RetentionPolicy.RUNTIME)
                    public @interface EnableBatchProcessing {
                    }
                    """,
            "io/awspring/cloud/sqs/annotation/SqsListener.java", """
                    package io.awspring.cloud.sqs.annotation;

                    import java.lang.annotation.Retention;
                    import java.lang.annotation.RetentionPolicy;

                    @Retention(RetentionPolicy.RUNTIME)
                    public @interface SqsListener {
                        String[] value() default {};

                        String[] queueNames() default {};
                    }
                    """,
            "org/springframework/cloud/stream/function/StreamBridge.java", """
                    package org.springframework.cloud.stream.function;

                    public class StreamBridge {
                    }
                    """);

    /** Stubs compiled into a jar (logast leaves class folders out of type attribution), and a classpath file with it. */
    private Path libraryClasspath(String name, Map<String, String> stubs) throws IOException {
        Path src = tmp.resolve(name + "/src");
        Path out = Files.createDirectories(tmp.resolve(name + "/classes"));
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "-cp",
                String.join(java.io.File.pathSeparator, Files.readAllLines(Path.of(CLASSPATH)).stream().filter(l -> !l.isBlank()).toList())));
        for (Map.Entry<String, String> e : new TreeMap<>(stubs).entrySet()) {
            Path f = src.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
            args.add(f.toString());
        }
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, err, args.toArray(String[]::new)))
                .as(name + " stubs compile:\n" + err).isZero();
        Path jar = tmp.resolve(name + "/" + name + ".jar");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar)); Stream<Path> s = Files.walk(out)) {
            for (Path c : s.filter(Files::isRegularFile).sorted().toList()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(out.relativize(c).toString().replace('\\', '/')));
                zip.write(Files.readAllBytes(c));
                zip.closeEntry();
            }
        }
        extraClasses = new Path[]{jar};
        Path cp = tmp.resolve("classpath-" + name + ".txt");
        List<String> lines = new ArrayList<>(Files.readAllLines(Path.of(CLASSPATH)));
        lines.add(jar.toString());
        Files.write(cp, lines);
        return cp;
    }

    /** #15: a Spring Batch tasklet and item processor, SQS listeners, Spring Cloud Stream functions, MVC router functions. */
    private static final IoAction INTAKE_ENTRYPOINTS = app -> {
        Path dir = app.resolve("src/main/java/com/example/e2e/intake");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("PledgeImportTasklet.java"), """
                package com.example.e2e.intake;

                import org.springframework.batch.core.StepContribution;
                import org.springframework.batch.core.scope.context.ChunkContext;
                import org.springframework.batch.core.step.tasklet.Tasklet;
                import org.springframework.batch.repeat.RepeatStatus;
                import org.springframework.stereotype.Component;

                /** One step of the nightly import job. */
                @Component
                public class PledgeImportTasklet implements Tasklet {
                    private final PledgeFeed feed;

                    PledgeImportTasklet(PledgeFeed feed) {
                        this.feed = feed;
                    }

                    @Override
                    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
                        feed.load();
                        return RepeatStatus.FINISHED;
                    }
                }
                """);
        Files.writeString(dir.resolve("PledgeFeed.java"), """
                package com.example.e2e.intake;

                import org.springframework.stereotype.Component;

                @Component
                public class PledgeFeed {
                    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PledgeFeed.class);

                    void load() {
                        log.info("Pledge feed loaded");
                    }
                }
                """);
        Files.writeString(dir.resolve("OrderLineProcessor.java"), """
                package com.example.e2e.intake;

                import com.example.e2e.order.Order;
                import org.springframework.batch.item.ItemProcessor;
                import org.springframework.stereotype.Component;

                /** A chunk step's processor: called once per order read. */
                @Component
                public class OrderLineProcessor implements ItemProcessor<Order, String> {
                    private final PledgeFeed feed;

                    OrderLineProcessor(PledgeFeed feed) {
                        this.feed = feed;
                    }

                    @Override
                    public String process(Order order) {
                        feed.load();
                        return order.customerId() + ":" + order.amountCents();
                    }
                }
                """);
        Files.writeString(dir.resolve("PledgeQueueListener.java"), """
                package com.example.e2e.intake;

                import io.awspring.cloud.sqs.annotation.SqsListener;
                import java.util.List;
                import org.springframework.stereotype.Component;

                /** One message at a time, and a batch: Spring Cloud AWS observes only the first, and only when switched on. */
                @Component
                public class PledgeQueueListener {
                    private final PledgeFeed feed;

                    PledgeQueueListener(PledgeFeed feed) {
                        this.feed = feed;
                    }

                    @SqsListener("pledge-requests")
                    public void onRequest(String pledgeId) {
                        feed.load();
                    }

                    @SqsListener(queueNames = "pledge-bulk")
                    public void onBulk(List<String> pledgeIds) {
                        feed.load();
                    }
                }
                """);
        Files.writeString(dir.resolve("IntakeFunctions.java"), """
                package com.example.e2e.intake;

                import com.example.e2e.order.Order;
                import java.util.function.Consumer;
                import java.util.function.Function;
                import java.util.function.Supplier;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.messaging.Message;

                /** Spring Cloud Stream binds the functions spring.cloud.function.definition names. */
                @Configuration
                public class IntakeFunctions {
                    @Bean
                    public Consumer<Message<Order>> orderIntake(OrderDesk intake) {
                        return message -> intake.accept(message.getPayload());
                    }

                    @Bean
                    public Function<String, String> pledgeNormalizer(CodeNormalizer normalizer) {
                        return normalizer::normalize;
                    }

                    @Bean
                    public Consumer<String> pledgeArchive(CodeNormalizer normalizer) {
                        return normalizer::archive;
                    }

                    @Bean
                    public Supplier<String> heartbeat() {
                        return () -> "alive";
                    }
                }
                """);
        Files.writeString(dir.resolve("OrderDesk.java"), """
                package com.example.e2e.intake;

                import com.example.e2e.order.Order;
                import org.springframework.stereotype.Component;

                @Component
                public class OrderDesk {
                    private final PledgeFeed feed;

                    OrderDesk(PledgeFeed feed) {
                        this.feed = feed;
                    }

                    public void accept(Order order) {
                        feed.load();
                    }
                }
                """);
        Files.writeString(dir.resolve("OrderLedger.java"), """
                package com.example.e2e.intake;

                import com.example.e2e.order.Order;
                import org.springframework.stereotype.Component;
                import org.springframework.transaction.event.TransactionalEventListener;

                /** Its own flow after the commit: an MdcScope (the order's), which measures nothing. */
                @Component
                public class OrderLedger {
                    private final PledgeFeed feed;

                    OrderLedger(PledgeFeed feed) {
                        this.feed = feed;
                    }

                    @TransactionalEventListener
                    public void onSettled(Order order) {
                        feed.load();
                    }
                }
                """);
        Files.writeString(dir.resolve("CodeNormalizer.java"), """
                package com.example.e2e.intake;

                import org.springframework.stereotype.Component;

                @Component
                public class CodeNormalizer {
                    public String normalize(String pledgeId) {
                        return pledgeId.trim();
                    }

                    public void archive(String pledgeId) {
                    }
                }
                """);
        Files.writeString(dir.resolve("PledgeRoutes.java"), """
                package com.example.e2e.intake;

                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.web.servlet.function.RequestPredicates;
                import org.springframework.web.servlet.function.RouterFunction;
                import org.springframework.web.servlet.function.RouterFunctions;
                import org.springframework.web.servlet.function.ServerResponse;

                /** Functional endpoints: handler methods instead of @RequestMapping. */
                @Configuration
                public class PledgeRoutes {
                    static final String BASE = "/pledge-routes";

                    @Bean
                    public RouterFunction<ServerResponse> pledgeRouter(PledgeRouteHandler handler) {
                        return RouterFunctions.route()
                                .path(BASE, builder -> builder
                                        .GET("/{pledgeId}", handler::show)
                                        .POST("", handler::register))
                                .DELETE(BASE + "/{pledgeId}", request -> handler.remove(request))
                                .build();
                    }

                    @Bean
                    public RouterFunction<ServerResponse> pledgeReplace(PledgeRouteHandler handler) {
                        return RouterFunctions.route(RequestPredicates.PUT("/pledge-routes/{pledgeId}"), handler::replace);
                    }
                }
                """);
        Files.writeString(dir.resolve("PledgeRouteHandler.java"), """
                package com.example.e2e.intake;

                import org.springframework.stereotype.Component;
                import org.springframework.web.servlet.function.ServerRequest;
                import org.springframework.web.servlet.function.ServerResponse;

                @Component
                public class PledgeRouteHandler {
                    private final PledgeFeed feed;

                    PledgeRouteHandler(PledgeFeed feed) {
                        this.feed = feed;
                    }

                    public ServerResponse show(ServerRequest request) {
                        return ServerResponse.ok().body(request.pathVariable("pledgeId"));
                    }

                    public ServerResponse register(ServerRequest request) {
                        return ServerResponse.accepted().build();
                    }

                    public ServerResponse remove(ServerRequest request) {
                        feed.load();
                        return ServerResponse.noContent().build();
                    }

                    public ServerResponse replace(ServerRequest request) {
                        feed.load();
                        return ServerResponse.noContent().build();
                    }
                }
                """);
        Path yml = app.resolve("src/main/resources/application.yml");
        Files.writeString(yml, Files.readString(yml).replaceFirst("spring:\n", """
                spring:
                  cloud:
                    function:
                      definition: orderIntake;pledgeNormalizer
                """));
    };

    private static final String INTAKE = "src/main/java/com/example/e2e/intake/";

    /**
     * #15: entrypoints that are not annotated controllers or Kafka/JMS/Rabbit listeners. A Spring Batch {@code Tasklet}
     * (one run per step: started/outcome lines) and item processor (per item: MDC of the order) are not observed on
     * Batch 5 without {@code @EnableBatchProcessing} or BatchObservabilityBeanPostProcessor: ObservedContext. Each
     * {@code @SqsListener} message is observed only with spring.cloud.aws.sqs.observation-enabled, a batch never:
     * ObservedContext. The handlers of the Spring Cloud Stream functions spring.cloud.function.definition names
     * (orderIntake, pledgeNormalizer; not pledgeArchive or the Supplier) are observed by Stream: MDC and entry line
     * only. The handlers of MVC router functions are HTTP endpoints with their paths (observed by Spring MVC). All
     * appear in traffic.yaml. Every file must equal e2e/expected-entrypoint-kinds; tracing alone and logging alone
     * each do their part; with the observation switched on (property, {@code @EnableBatchProcessing}) nothing wraps.
     * #99: an observed step, item, SQS message or Stream handler whose lifecycle line names a vocabulary term ("Order desk
     * completed") gets no second timer (BUSINESS_EVENT_UNMEASURED); a handler with only an MdcScope (OrderLedger) gets its
     * timer in pass 1, as the rescan would decide.
     */
    @Test
    void seesBatchStepsSqsListenersStreamFunctionsAndRouterFunctions() throws IOException {
        String classpath = libraryClasspath("entrypoint-libraries", ENTRYPOINT_LIBRARY_STUBS).toString();
        Map<String, String> actual = migrate(tmp.resolve("app"), classpath, INTAKE_ENTRYPOINTS);
        assertThat(actual.get(".logast/decisions/traffic.yaml")).as("every new kind is an entrypoint")
                .contains("\"tasklet PledgeImportTasklet.execute\"")
                .contains("\"item processor OrderLineProcessor.process\"")
                .contains("\"@SqsListener pledge-requests\"").contains("\"@SqsListener pledge-bulk\"")
                .contains("\"stream orderIntake-in-0\"").contains("\"stream pledgeNormalizer-in-0\"")
                .doesNotContain("pledgeArchive").doesNotContain("heartbeat")
                .contains("\"GET /pledge-routes/{pledgeId}\"").contains("\"POST /pledge-routes\"")
                .contains("\"DELETE /pledge-routes/{pledgeId}\"").contains("\"PUT /pledge-routes/{pledgeId}\"");
        assertThat(actual.get(INTAKE + "PledgeImportTasklet.java")).as("Batch 5 without observability: the step run is wrapped")
                .contains("ObservedContext.entrypoint(IntakeMetrics.PLEDGE_IMPORT_TASKLET_EXECUTE)");
        assertThat(actual.get(INTAKE + "OrderLineProcessor.java")).as("per item, with the order's MDC")
                .contains("ObservedContext.entrypoint(IntakeMetrics.ORDER_LINE_PROCESSOR_PROCESS)").contains(".mdc(\"orderId\", order.id())");
        assertThat(actual.get(INTAKE + "PledgeQueueListener.java")).as("SQS observation off by default; batches never")
                .contains("ObservedContext.entrypoint(IntakeMetrics.PLEDGE_QUEUE_LISTENER_ON_REQUEST)")
                .contains("ObservedContext.entrypoint(IntakeMetrics.PLEDGE_QUEUE_LISTENER_ON_BULK)");
        assertThat(actual.get(".logast/reports/apply-trace.txt")).as("#99: Stream times \"Order desk completed\": no second timer")
                .doesNotContainPattern("BUSINESS_EVENT_UNMEASURED \\S+ \\S+/intake/(OrderDesk|PledgeQueueListener|PledgeImportTasklet|OrderLineProcessor)");
        assertThat(actual.get(INTAKE + "OrderLedger.java")).as("#99: an MdcScope measures nothing: the timer comes in pass 1; "
                        + "#20: its MDC goes into the ObservedContext chain, not into an MdcScope around it")
                .contains("        ObservedContext.entrypoint(IntakeMetrics.ORDER_LEDGER)\n                .mdc(\"orderId\", order.id())\n")
                .doesNotContain("MdcScope.of");
        assertThat(actual.get(INTAKE + "OrderDesk.java")).as("observed by Spring Cloud Stream: MDC only")
                .doesNotContain("ObservedContext").contains("MdcScope.of(\"orderId\", order.id())");
        assertThat(actual.get(INTAKE + "CodeNormalizer.java")).as("bound: MDC; pledgeArchive is not bound")
                .contains("MdcScope.of(\"pledgeId\", pledgeId)").containsOnlyOnce("MdcScope.of");
        assertThat(actual.get(INTAKE + "PledgeRouteHandler.java")).as("observed by Spring MVC: no wrapper")
                .doesNotContain("ObservedContext");
        golden(EXPECTED.resolveSibling("expected-entrypoint-kinds"), actual);

        // the tracing pack alone: the wrappers, no MDC scopes or lines (the logging pack's)
        Map<String, String> tracing = migrate(tmp.resolve("tracing-only"), classpath, withPacks(INTAKE_ENTRYPOINTS, "[tracing]", ""));
        assertThat(tracing.get(INTAKE + "PledgeImportTasklet.java")).contains("ObservedContext.entrypoint(\"pledge-import-tasklet.execute\")");
        assertThat(tracing.get(INTAKE + "PledgeQueueListener.java")).contains("ObservedContext.entrypoint(\"pledge-queue-listener.on-bulk\")");
        assertThat(tracing.get(INTAKE + "OrderDesk.java")).doesNotContain("MdcScope").doesNotContain("ObservedContext");

        // the logging pack alone: MDC scopes for every kind, no ObservedContext
        Map<String, String> logging = migrate(tmp.resolve("logging-only"), classpath, withPacks(INTAKE_ENTRYPOINTS, "[logging]", ""));
        assertThat(logging.get(INTAKE + "OrderDesk.java")).contains("MdcScope.of(\"orderId\", order.id())");
        assertThat(logging.get(INTAKE + "OrderLineProcessor.java")).contains("MdcScope.of(\"orderId\", order.id())")
                .doesNotContain("ObservedContext");
        assertThat(logging.get(INTAKE + "PledgeQueueListener.java")).contains("MdcScope.of(\"pledgeId\", pledgeId)")
                .doesNotContain("ObservedContext");

        // observation switched on: SQS by its property (a batch still is not observed), Batch by @EnableBatchProcessing
        Map<String, String> observed = migrate(tmp.resolve("observed"), classpath, app -> {
            INTAKE_ENTRYPOINTS.run(app);
            Path yml = app.resolve("src/main/resources/application.yml");
            Files.writeString(yml, Files.readString(yml).replaceFirst("  cloud:\n", "  cloud:\n    aws:\n      sqs:\n        observation-enabled: true\n"));
            Files.writeString(app.resolve(INTAKE + "BatchSetup.java"), """
                    package com.example.e2e.intake;

                    import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
                    import org.springframework.context.annotation.Configuration;

                    @Configuration
                    @EnableBatchProcessing
                    public class BatchSetup {
                    }
                    """);
        });
        // #99: every pack, so the observed step, item and SQS message log vocabulary-named lifecycle lines ("Pledge
        // import tasklet started"): the framework's observation times them, no second timer (BUSINESS_EVENT_UNMEASURED)
        assertThat(observed.get(INTAKE + "PledgeImportTasklet.java")).doesNotContain("ObservedContext");
        assertThat(observed.get(INTAKE + "OrderLineProcessor.java")).doesNotContain("ObservedContext");
        assertThat(observed.get(INTAKE + "PledgeQueueListener.java"))
                .doesNotContain("ObservedContext.entrypoint(IntakeMetrics.PLEDGE_QUEUE_LISTENER_ON_REQUEST)")
                .as("only the batch listener, which SQS never observes, is wrapped")
                .containsOnlyOnce("ObservedContext.entrypoint(").contains("ObservedContext.entrypoint(IntakeMetrics.PLEDGE_QUEUE_LISTENER_ON_BULK)");
        assertThat(observed.get(".logast/reports/apply-trace.txt")).as("no second timer on an observed handler")
                .doesNotContainPattern("BUSINESS_EVENT_UNMEASURED \\S+ \\S+/intake/(OrderDesk|PledgeQueueListener|PledgeImportTasklet|OrderLineProcessor)");
    }

    /**
     * Stand-ins for Spring Data Redis pub/sub, Spring Integration's {@code @ServiceActivator}, grpc-java (with a generated
     * service base class) and Spring gRPC, none of which is on the e2e classpath (#15).
     */
    private static final Map<String, String> RELAY_LIBRARY_STUBS = Map.ofEntries(
            Map.entry("org/springframework/data/redis/connection/Message.java", """
                    package org.springframework.data.redis.connection;

                    public interface Message {
                        byte[] getBody();

                        byte[] getChannel();
                    }
                    """),
            Map.entry("org/springframework/data/redis/connection/MessageListener.java", """
                    package org.springframework.data.redis.connection;

                    public interface MessageListener {
                        void onMessage(Message message, byte[] pattern);
                    }
                    """),
            Map.entry("org/springframework/data/redis/listener/Topic.java", """
                    package org.springframework.data.redis.listener;

                    public interface Topic {
                        String getTopic();
                    }
                    """),
            Map.entry("org/springframework/data/redis/listener/ChannelTopic.java", """
                    package org.springframework.data.redis.listener;

                    public class ChannelTopic implements Topic {
                        private final String name;

                        public ChannelTopic(String name) {
                            this.name = name;
                        }

                        public String getTopic() {
                            return name;
                        }
                    }
                    """),
            Map.entry("org/springframework/data/redis/listener/RedisMessageListenerContainer.java", """
                    package org.springframework.data.redis.listener;

                    import org.springframework.data.redis.connection.MessageListener;

                    public class RedisMessageListenerContainer {
                        public void addMessageListener(MessageListener listener, Topic topic) {
                        }
                    }
                    """),
            Map.entry("org/springframework/data/redis/listener/adapter/MessageListenerAdapter.java", """
                    package org.springframework.data.redis.listener.adapter;

                    import org.springframework.data.redis.connection.Message;
                    import org.springframework.data.redis.connection.MessageListener;

                    public class MessageListenerAdapter implements MessageListener {
                        public MessageListenerAdapter() {
                        }

                        public MessageListenerAdapter(Object delegate) {
                        }

                        public MessageListenerAdapter(Object delegate, String defaultListenerMethod) {
                        }

                        public void setDefaultListenerMethod(String defaultListenerMethod) {
                        }

                        public void onMessage(Message message, byte[] pattern) {
                        }
                    }
                    """),
            Map.entry("org/springframework/integration/annotation/ServiceActivator.java", """
                    package org.springframework.integration.annotation;

                    import java.lang.annotation.Retention;
                    import java.lang.annotation.RetentionPolicy;

                    @Retention(RetentionPolicy.RUNTIME)
                    public @interface ServiceActivator {
                        String inputChannel() default "";

                        String outputChannel() default "";
                    }
                    """),
            Map.entry("io/grpc/ServerServiceDefinition.java", """
                    package io.grpc;

                    public final class ServerServiceDefinition {
                    }
                    """),
            Map.entry("io/grpc/BindableService.java", """
                    package io.grpc;

                    public interface BindableService {
                        ServerServiceDefinition bindService();
                    }
                    """),
            Map.entry("io/grpc/stub/StreamObserver.java", """
                    package io.grpc.stub;

                    public interface StreamObserver<V> {
                        void onNext(V value);

                        void onError(Throwable t);

                        void onCompleted();
                    }
                    """),
            Map.entry("com/example/proto/PledgeRequest.java", """
                    package com.example.proto;

                    public final class PledgeRequest {
                        public String getPledgeId() {
                            return "";
                        }
                    }
                    """),
            Map.entry("com/example/proto/PledgeReply.java", """
                    package com.example.proto;

                    public final class PledgeReply {
                        public static PledgeReply of(String status) {
                            return new PledgeReply();
                        }
                    }
                    """),
            Map.entry("com/example/proto/PledgeServiceGrpc.java", """
                    package com.example.proto;

                    import io.grpc.stub.StreamObserver;

                    /** What protoc-gen-grpc-java generates: the service base class implementations extend. */
                    public final class PledgeServiceGrpc {
                        public abstract static class PledgeServiceImplBase implements io.grpc.BindableService {
                            public void lookup(PledgeRequest request, StreamObserver<PledgeReply> responseObserver) {
                            }

                            public void watch(PledgeRequest request, StreamObserver<PledgeReply> responseObserver) {
                            }

                            public StreamObserver<PledgeRequest> upload(StreamObserver<PledgeReply> responseObserver) {
                                return null;
                            }

                            public final io.grpc.ServerServiceDefinition bindService() {
                                return new io.grpc.ServerServiceDefinition();
                            }
                        }
                    }
                    """),
            Map.entry("org/springframework/grpc/server/GrpcServerFactory.java", """
                    package org.springframework.grpc.server;

                    public interface GrpcServerFactory {
                    }
                    """));

    /** #15: Redis pub/sub listeners and adapter delegates, Spring Integration service activators, a gRPC service. */
    private static final IoAction RELAY_ENTRYPOINTS = app -> {
        Path dir = app.resolve("src/main/java/com/example/e2e/relay");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("RelayLog.java"), """
                package com.example.e2e.relay;

                import org.springframework.stereotype.Component;

                @Component
                public class RelayLog {
                    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RelayLog.class);

                    void record(String what) {
                        log.info("Relay recorded {}", what);
                    }

                    /** Only the Redis listener calls it: the container logs what it rethrows. */
                    void forward(byte[] body) {
                        try {
                            record(new String(body, java.nio.charset.StandardCharsets.UTF_8));
                        } catch (RuntimeException e) {
                            log.error("Relay forward failed", e);
                            throw e;
                        }
                    }
                }
                """);
        Files.writeString(dir.resolve("PledgeChannelListener.java"), """
                package com.example.e2e.relay;

                import org.springframework.data.redis.connection.Message;
                import org.springframework.data.redis.connection.MessageListener;
                import org.springframework.stereotype.Component;

                /** A Redis pub/sub listener registered on the container. */
                @Component
                public class PledgeChannelListener implements MessageListener {
                    private final RelayLog relayLog;

                    PledgeChannelListener(RelayLog relayLog) {
                        this.relayLog = relayLog;
                    }

                    @Override
                    public void onMessage(Message message, byte[] pattern) {
                        relayLog.forward(message.getBody());
                    }
                }
                """);
        Files.writeString(dir.resolve("PledgeRelay.java"), """
                package com.example.e2e.relay;

                import org.springframework.stereotype.Component;

                /** A plain bean Redis calls through MessageListenerAdapters. */
                @Component
                public class PledgeRelay {
                    private final RelayLog relayLog;

                    PledgeRelay(RelayLog relayLog) {
                        this.relayLog = relayLog;
                    }

                    public void onPledge(String pledgeId) {
                        relayLog.record(pledgeId);
                    }

                    public void onRefund(String refundId, String channel) {
                        relayLog.record(refundId);
                    }

                    public void handleMessage(String voucherId) {
                        relayLog.record(voucherId);
                    }
                }
                """);
        Files.writeString(dir.resolve("RedisRelaySetup.java"), """
                package com.example.e2e.relay;

                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.data.redis.listener.ChannelTopic;
                import org.springframework.data.redis.listener.RedisMessageListenerContainer;
                import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

                @Configuration
                public class RedisRelaySetup {
                    static final String REFUND_METHOD = "onRefund";

                    @Value("${relay.method:onPledge}")
                    private String relayMethod;

                    @Bean
                    public RedisMessageListenerContainer relayContainer(PledgeChannelListener listener, PledgeRelay relay) {
                        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
                        container.addMessageListener(listener, new ChannelTopic("pledge-events"));
                        container.addMessageListener(new MessageListenerAdapter(relay, "onPledge"), new ChannelTopic("pledges"));
                        container.addMessageListener(new MessageListenerAdapter(relay, REFUND_METHOD), new ChannelTopic("refunds"));
                        container.addMessageListener(new MessageListenerAdapter(relay), new ChannelTopic("vouchers"));
                        return container;
                    }

                    @Bean
                    public MessageListenerAdapter configuredAdapter(PledgeRelay relay) {
                        return new MessageListenerAdapter(relay, relayMethod);
                    }
                }
                """);
        Files.writeString(dir.resolve("PledgeActivator.java"), """
                package com.example.e2e.relay;

                import org.springframework.integration.annotation.ServiceActivator;
                import org.springframework.stereotype.Component;

                /** A Spring Integration endpoint: each message on the input channel calls the method. */
                @Component
                public class PledgeActivator {
                    private final RelayLog relayLog;

                    PledgeActivator(RelayLog relayLog) {
                        this.relayLog = relayLog;
                    }

                    @ServiceActivator(inputChannel = "pledgeRequests")
                    public void allot(String pledgeId) {
                        relayLog.record(pledgeId);
                    }

                    @ServiceActivator(inputChannel = "pledgeReturns")
                    public void giveBack(PledgeReturn command) {
                        relayLog.record(command.pledgeId() + " x" + command.quantity());
                    }
                }
                """);
        Files.writeString(dir.resolve("PledgeReturn.java"), """
                package com.example.e2e.relay;

                /** A message payload: its pledgeId accessor is the MDC value. */
                public record PledgeReturn(String pledgeId, int quantity) {
                }
                """);
        Files.writeString(dir.resolve("VoucherActivator.java"), """
                package com.example.e2e.relay;

                import org.springframework.context.annotation.Bean;
                import org.springframework.integration.annotation.ServiceActivator;
                import org.springframework.messaging.MessageHandler;
                import org.springframework.stereotype.Component;

                @Component
                public class VoucherActivator {
                    private final RelayLog relayLog;

                    VoucherActivator(RelayLog relayLog) {
                        this.relayLog = relayLog;
                    }

                    @ServiceActivator(inputChannel = "voucherRequests")
                    public void issue(String voucherId) {
                        relayLog.record(voucherId);
                    }

                    /** Defines a handler bean: the method is configuration, not a handler. */
                    @Bean
                    @ServiceActivator(inputChannel = "voucherAudits")
                    public MessageHandler voucherAuditHandler() {
                        return message -> relayLog.record("audit");
                    }
                }
                """);
        Files.writeString(dir.resolve("PledgeGrpcService.java"), """
                package com.example.e2e.relay;

                import com.example.proto.PledgeReply;
                import com.example.proto.PledgeRequest;
                import com.example.proto.PledgeServiceGrpc;
                import io.grpc.stub.StreamObserver;
                import java.util.concurrent.Executor;
                import org.springframework.stereotype.Service;

                /** A gRPC service: one call answered before the method returns, one answered later on another thread. */
                @Service
                public class PledgeGrpcService extends PledgeServiceGrpc.PledgeServiceImplBase {
                    private final RelayLog relayLog;
                    private final Executor relayExecutor;

                    PledgeGrpcService(RelayLog relayLog, Executor relayExecutor) {
                        this.relayLog = relayLog;
                        this.relayExecutor = relayExecutor;
                    }

                    @Override
                    public void lookup(PledgeRequest request, StreamObserver<PledgeReply> responseObserver) {
                        String pledgeId = request.getPledgeId();
                        relayLog.record(pledgeId);
                        responseObserver.onNext(PledgeReply.of("found"));
                        responseObserver.onCompleted();
                    }

                    @Override
                    public void watch(PledgeRequest request, StreamObserver<PledgeReply> responseObserver) {
                        String pledgeId = request.getPledgeId();
                        relayLog.record(pledgeId);
                        relayExecutor.execute(() -> {
                            responseObserver.onNext(PledgeReply.of("changed"));
                            responseObserver.onCompleted();
                        });
                    }

                    @Override
                    public StreamObserver<PledgeRequest> upload(StreamObserver<PledgeReply> responseObserver) {
                        return new StreamObserver<>() {
                            @Override
                            public void onNext(PledgeRequest value) {
                                relayLog.record(value.getPledgeId());
                            }

                            @Override
                            public void onError(Throwable t) {
                                responseObserver.onError(t);
                            }

                            @Override
                            public void onCompleted() {
                                responseObserver.onCompleted();
                            }
                        };
                    }
                }
                """);
    };

    private static final String RELAY = "src/main/java/com/example/e2e/relay/";

    /**
     * #15: Redis pub/sub, Spring Integration and gRPC entrypoints. A Redis {@code MessageListener} and the methods a
     * {@code MessageListenerAdapter} delegates to (a literal name, a constant, the default handleMessage) are not observed
     * by Spring Data Redis: ObservedContext; an adapter whose method name comes from a property is reported. A
     * {@code @ServiceActivator} method is observed only when Spring Integration's observation patterns match it (none by
     * default): ObservedContext; a {@code @Bean @ServiceActivator} is not a handler. The unary and server-streaming
     * methods of a gRPC service are observed by Spring gRPC: MDC and lines only (the flow logs; the pledgeId MDC
     * comes from the protobuf request's getter, as it does from a record payload's accessor at the second
     * {@code @ServiceActivator}); with its observation switched off, the
     * method that completes in its body is wrapped and the one that completes later on another thread is reported.
     * Client streaming (returns the request observer) is not an entrypoint. Every file must equal
     * e2e/expected-relay-entrypoints; tracing alone does its part.
     */
    @Test
    void seesRedisListenersServiceActivatorsAndGrpcServices() throws IOException {
        String classpath = libraryClasspath("relay-libraries", RELAY_LIBRARY_STUBS).toString();
        Map<String, String> actual = migrate(tmp.resolve("app"), classpath, RELAY_ENTRYPOINTS);
        assertThat(actual.get(".logast/decisions/traffic.yaml")).as("every new kind is an entrypoint")
                .contains("\"redis PledgeChannelListener.onMessage\"").contains("\"redis PledgeRelay.onPledge\"")
                .contains("\"redis PledgeRelay.onRefund\"").contains("\"redis PledgeRelay.handleMessage\"")
                .contains("\"@ServiceActivator pledgeRequests\"").contains("\"@ServiceActivator voucherRequests\"")
                .doesNotContain("voucherAudits").doesNotContain("voucherAuditHandler")
                .contains("\"grpc PledgeGrpcService.lookup\"").contains("\"grpc PledgeGrpcService.watch\"")
                .doesNotContain("PledgeGrpcService.upload");
        assertThat(actual.get(RELAY + "PledgeChannelListener.java")).as("Redis pub/sub is not observed")
                .contains("ObservedContext.entrypoint(");
        assertThat(actual.get(RELAY + "PledgeRelay.java")).as("each adapter delegate, with its MDC")
                .contains("ObservedContext.entrypoint(RelayMetrics.PLEDGE_RELAY_ON_PLEDGE)").contains(".mdc(\"pledgeId\", pledgeId)")
                .contains("ObservedContext.entrypoint(RelayMetrics.PLEDGE_RELAY_ON_REFUND)")
                .contains("ObservedContext.entrypoint(RelayMetrics.PLEDGE_RELAY_HANDLE_MESSAGE)");
        assertThat(actual.get("FINDINGS.txt")).as("an adapter method from a property is reported")
                .contains("MessageListenerAdapter for PledgeRelay: the handler Redis calls is not known");
        assertThat(actual.get(RELAY + "PledgeActivator.java")).as("Spring Integration observes nothing by default; a record "
                        + "payload's accessor is the MDC value").contains("ObservedContext.entrypoint(")
                .contains(".mdc(\"pledgeId\", command.pledgeId())").doesNotContain(".mdc(\"quantity\"");
        assertThat(actual.get(RELAY + "PledgeGrpcService.java")).as("observed by Spring gRPC: no wrapper (its flow logs), the "
                        + "MDC scope from the protobuf request's getter").doesNotContain("ObservedContext")
                .contains("    public void lookup(PledgeRequest request, StreamObserver<PledgeReply> responseObserver) {\n"
                        + "        try (var mdc = MdcScope.of(\"pledgeId\", request.getPledgeId())) {\n");
        assertThat(actual.get(RELAY + "RelayLog.java")).as("RedisMessageListenerContainer logs a listener's exception: the "
                + "logged-and-rethrown line goes").doesNotContain("Relay forward failed");
        String lifecycle = run(tmp.resolve("app"), "lifecycle", tmp.resolve("app").toString(), "--classpath-file", classpath);
        assertThat(lifecycle.indexOf("grpc PledgeGrpcService.watch")).as("gRPC: the call's status is its outcome, like HTTP:\n" + lifecycle)
                .isGreaterThan(lifecycle.indexOf("http (")).isLessThan(lifecycle.indexOf("message ("));
        golden(EXPECTED.resolveSibling("expected-relay-entrypoints"), actual);

        // the tracing pack alone: the wrappers, no MDC scopes or lines
        Map<String, String> tracing = migrate(tmp.resolve("tracing-only"), classpath, withPacks(RELAY_ENTRYPOINTS, "[tracing]", ""));
        assertThat(tracing.get(RELAY + "PledgeRelay.java")).contains("ObservedContext.entrypoint(\"pledge-relay.on-pledge\")")
                .doesNotContain("MdcScope");
        assertThat(tracing.get(RELAY + "PledgeActivator.java")).contains("ObservedContext.entrypoint(\"pledge-activator.allot\")");
        assertThat(tracing.get(RELAY + "PledgeGrpcService.java")).doesNotContain("ObservedContext").doesNotContain("MdcScope");

        // Spring Integration observes pledgeActivator's handler by its pattern; Spring gRPC observation switched off
        Map<String, String> switched = migrate(tmp.resolve("switched"), classpath, app -> {
            RELAY_ENTRYPOINTS.run(app);
            Path yml = app.resolve("src/main/resources/application.yml");
            Files.writeString(yml, Files.readString(yml).replaceFirst("spring:\n", """
                    spring:
                      integration:
                        management:
                          observation-patterns: "!voucher*, pledgeActivator.*"
                      grpc:
                        server:
                          observation:
                            enabled: false
                    """));
        });
        assertThat(switched.get(RELAY + "PledgeActivator.java")).doesNotContain("ObservedContext");
        assertThat(switched.get(RELAY + "VoucherActivator.java")).contains("ObservedContext.entrypoint(");
        assertThat(switched.get(RELAY + "PledgeGrpcService.java")).as("only the call that completes in its body is wrapped")
                .containsOnlyOnce("ObservedContext.entrypoint(").contains("ObservedContext.entrypoint(RelayMetrics.PLEDGE_GRPC_SERVICE_LOOKUP)");
        assertThat(switched.get("FINDINGS.txt")).contains("grpc PledgeGrpcService.watch is not observed");
    }

    /** #14: a WebFlux controller and reactive router functions next to the existing Mono service (messaging). */
    private static final IoAction REACTIVE_ENTRYPOINTS = app -> {
        Path dir = app.resolve("src/main/java/com/example/e2e/reactive");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("ReactivePledgeController.java"), """
                package com.example.e2e.reactive;

                import com.example.e2e.messaging.ReactivePledgeLookup;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;
                import reactor.core.publisher.Flux;
                import reactor.core.publisher.Mono;

                /** Spring WebFlux: each method assembles the pipeline the request runs through. */
                @RestController
                @RequestMapping("/reactive-pledges")
                public class ReactivePledgeController {
                    private static final Logger log = LoggerFactory.getLogger(ReactivePledgeController.class);
                    private final ReactivePledgeLookup lookup;

                    ReactivePledgeController(ReactivePledgeLookup lookup) {
                        this.lookup = lookup;
                    }

                    @GetMapping("/{pledgeId}")
                    public Mono<String> show(@PathVariable String pledgeId) {
                        return lookup.find(pledgeId);
                    }

                    @PostMapping("/{pledgeId}/hold")
                    public Mono<String> hold(@PathVariable String pledgeId) {
                        return lookup.find(pledgeId).map(String::toUpperCase);
                    }

                    @GetMapping("/{pledgeId}/preview")
                    public Mono<String> preview(@PathVariable String pledgeId) {
                        return lookup.find(pledgeId)
                                .onErrorResume(e -> Mono.just("unknown"));
                    }

                    @PostMapping("/{pledgeId}/release")
                    public Mono<String> release(@PathVariable String pledgeId) {
                        return lookup.find(pledgeId)
                                .doOnNext(found -> log.info("Pledge released [pledgeId={}]", pledgeId));
                    }

                    @GetMapping("/recent")
                    public Flux<String> recent() {
                        return Flux.just("first", "second");
                    }
                }
                """);
        Files.writeString(dir.resolve("ReactivePledgeRoutes.java"), """
                package com.example.e2e.reactive;

                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.web.reactive.function.server.RequestPredicates;
                import org.springframework.web.reactive.function.server.RouterFunction;
                import org.springframework.web.reactive.function.server.RouterFunctions;
                import org.springframework.web.reactive.function.server.ServerResponse;

                /** WebFlux functional endpoints. */
                @Configuration
                public class ReactivePledgeRoutes {
                    @Bean
                    public RouterFunction<ServerResponse> reactivePledgeRouter(ReactivePledgeHandler handler) {
                        return RouterFunctions.route()
                                .path("/reactive-routes", builder -> builder
                                        .GET("/{pledgeId}", handler::show)
                                        .POST("", handler::register))
                                .build();
                    }

                    @Bean
                    public RouterFunction<ServerResponse> reactivePledgeRemoval(ReactivePledgeHandler handler) {
                        return RouterFunctions.route(RequestPredicates.DELETE("/reactive-routes/{pledgeId}"), handler::remove);
                    }
                }
                """);
        Files.writeString(dir.resolve("ReactivePledgeHandler.java"), """
                package com.example.e2e.reactive;

                import com.example.e2e.messaging.ReactivePledgeLookup;
                import org.springframework.stereotype.Component;
                import org.springframework.web.reactive.function.server.ServerRequest;
                import org.springframework.web.reactive.function.server.ServerResponse;
                import reactor.core.publisher.Mono;

                @Component
                public class ReactivePledgeHandler {
                    private final ReactivePledgeLookup lookup;

                    ReactivePledgeHandler(ReactivePledgeLookup lookup) {
                        this.lookup = lookup;
                    }

                    public Mono<ServerResponse> show(ServerRequest request) {
                        String pledgeId = request.pathVariable("pledgeId");
                        return lookup.find(pledgeId).flatMap(found -> ServerResponse.ok().bodyValue(found));
                    }

                    public Mono<ServerResponse> register(ServerRequest request) {
                        return request.bodyToMono(String.class).flatMap(body -> ServerResponse.accepted().build());
                    }

                    public Mono<ServerResponse> remove(ServerRequest request) {
                        return lookup.find(request.pathVariable("pledgeId")).then(ServerResponse.noContent().build());
                    }
                }
                """);
    };

    private static final String REACTIVE = "src/main/java/com/example/e2e/reactive/";

    /**
     * #14: Spring WebFlux. The annotated controller's methods and the handlers of WebFlux router functions are HTTP
     * entrypoints (traffic.yaml), observed by WebFlux's server observation: no wrapper, and no MdcScope around a Mono
     * (it would end before the pipeline runs): the pledgeId MDC is a report (MISSING_DOMAIN_MDC) naming the Reactor
     * Context route. Reactor restores the trace in its operators only with automatic context propagation
     * (REACTIVE_CONTEXT_LOSS): the e2e classpath has no Spring Boot ReactorAutoConfiguration (Boot 4 without
     * spring-boot-reactor), so spring.reactor.context-propagation would do nothing and the hook goes first into main; with
     * it on the classpath (stand-in jar) the property is set instead. An unfiltered onErrorResume to a fallback is the
     * Reactor form of CATCH_ALL_DEFAULT. Every file must equal e2e/expected-reactive; tracing alone and logging alone each
     * switch propagation on; without context-propagation on the classpath the finding names it.
     */
    @Test
    void seesWebFluxControllersAndRouterFunctionsAndSetsUpContextPropagation() throws IOException {
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, REACTIVE_ENTRYPOINTS);
        assertThat(actual.get(".logast/decisions/traffic.yaml")).as("annotated WebFlux controllers and WebFlux router handlers are entrypoints")
                .contains("\"GET /reactive-pledges/{pledgeId}\"").contains("\"GET /reactive-routes/{pledgeId}\"")
                .contains("\"POST /reactive-routes\"").contains("\"DELETE /reactive-routes/{pledgeId}\"");
        assertThat(actual.get(MAIN_CLASS)).as("Boot 4 without spring-boot-reactor: the hook, first in main")
                .contains("import reactor.core.publisher.Hooks;")
                .contains("    public static void main(String[] args) {\n        Hooks.enableAutomaticContextPropagation();\n");
        assertThat(actual.get("src/main/resources/application.yml")).doesNotContain("reactor");
        assertThat(actual.get(REACTIVE + "ReactivePledgeController.java")).as("no scope or wrapper around a Mono")
                .doesNotContain("MdcScope").doesNotContain("ObservedContext");
        assertThat(actual.get("FINDINGS.txt")).as("the pledgeId MDC is a report on the reactive entrypoints")
                .contains("MISSING_DOMAIN_MDC  src/main/java/com/example/e2e/reactive/ReactivePledgeController.java:26")
                .contains("pledgeId not in MDC for ReactivePledgeController.show")
                .as("onErrorResume to a fallback").contains("onErrorResume(e -> Mono.just(\"unknown\")) turns every error")
                .as("switched on: no finding left").doesNotContain("  REACTIVE_CONTEXT_LOSS  ");
        golden(EXPECTED.resolveSibling("expected-reactive"), actual);

        // Spring Boot's ReactorAutoConfiguration on the classpath (Boot 4's spring-boot-reactor): the property does it
        String boot = libraryClasspath("reactor-autoconfiguration", Map.of(
                "org/springframework/boot/reactor/autoconfigure/ReactorAutoConfiguration.java", """
                        package org.springframework.boot.reactor.autoconfigure;

                        public final class ReactorAutoConfiguration {
                        }
                        """)).toString();
        Map<String, String> property = migrate(tmp.resolve("property"), boot, REACTIVE_ENTRYPOINTS);
        assertThat(property.get("src/main/resources/application.yml")).contains("  reactor:\n    context-propagation: auto\n");
        assertThat(property.get(MAIN_CLASS)).doesNotContain("Hooks");

        // each pack on its own switches it on: the trace (tracing) and the trace ids in log lines (logging)
        for (String pack : List.of("tracing", "logging")) {
            Map<String, String> alone = migrate(tmp.resolve(pack + "-only"), CLASSPATH, withPacks(REACTIVE_ENTRYPOINTS, "[" + pack + "]", ""));
            assertThat(alone.get(MAIN_CLASS)).as(pack + " alone").contains("Hooks.enableAutomaticContextPropagation();");
        }

        // without io.micrometer:context-propagation Reactor restores nothing, hook or not: the finding says so
        Path missing = tmp.resolve("no-context-propagation");
        copy(BEFORE, missing);
        REACTIVE_ENTRYPOINTS.run(missing);
        Path cp = tmp.resolve("classpath-no-context-propagation.txt");
        Files.write(cp, Files.readAllLines(Path.of(CLASSPATH)).stream().filter(l -> !l.contains("context-propagation")).toList());
        assertThat(run(missing, "scan", missing.toString(), "--classpath-file", cp.toString()))
                .contains("REACTIVE_CONTEXT_LOSS").contains("io.micrometer:context-propagation is not on the classpath");
    }

    private static final String MAIN_CLASS = "src/main/java/com/example/e2e/E2eApplication.java";

    /** #102: a WebFlux-only service's error handling: a reactive controller with request bodies and its controller advice. */
    private static final IoAction WEBFLUX_ERRORS = app -> {
        Path build = app.resolve("build.gradle.kts");
        Files.writeString(build, Files.readString(build).replace("spring-boot-starter-webmvc", "spring-boot-starter-webflux"));
        Path dir = app.resolve("src/main/java/com/example/e2e/crate");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("CrateRequest.java"), """
                package com.example.e2e.crate;

                import jakarta.validation.constraints.NotBlank;

                public record CrateRequest(@NotBlank String label) {
                }
                """);
        Files.writeString(dir.resolve("CrateAnswer.java"), """
                package com.example.e2e.crate;

                public record CrateAnswer(String state) {
                }
                """);
        for (String name : List.of("CrateMissing", "CrateLate", "CrateBlocked", "CrateSealed")) {
            Files.writeString(dir.resolve(name + ".java"), """
                    package com.example.e2e.crate;

                    public class %s extends RuntimeException {
                        public %s(String message) {
                            super(message);
                        }
                    }
                    """.formatted(name, name));
        }
        Files.writeString(dir.resolve("CrateController.java"), """
                package com.example.e2e.crate;

                import jakarta.validation.Valid;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.PutMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RestController;
                import org.springframework.web.reactive.function.client.WebClient;
                import reactor.core.publisher.Mono;

                @RestController
                public class CrateController {
                    private final WebClient labels = WebClient.create("http://labels");

                    @PostMapping("/crates")
                    public Mono<String> register(@Valid @RequestBody CrateRequest request) {
                        return Mono.just(request.label());
                    }

                    @PutMapping("/crates/{crateCode}")
                    public Mono<String> relabel(@PathVariable String crateCode, @RequestBody CrateRequest request) {
                        return Mono.just(crateCode + request.label());
                    }

                    @GetMapping("/crates/{crateCode}")
                    public Mono<String> show(@PathVariable String crateCode) {
                        return crateCode.isBlank() ? Mono.error(new CrateMissing("no crate code")) : Mono.just(crateCode);
                    }

                    @PostMapping("/crates/{crateCode}/seal")
                    public Mono<String> seal(@PathVariable String crateCode) {
                        return Mono.error(() -> new CrateSealed(crateCode));
                    }

                    @GetMapping("/crates/{crateCode}/label")
                    public Mono<String> label(@PathVariable String crateCode) {
                        return labels.get().uri("/labels/{code}", crateCode).retrieve().bodyToMono(String.class);
                    }

                    @GetMapping("/crates/{crateCode}/fallback-label")
                    public Mono<String> fallbackLabel(@PathVariable String crateCode) {
                        return labels.get().uri("/labels/{code}", crateCode).retrieve().bodyToMono(String.class)
                                .onErrorResume(e -> Mono.just("unlabelled"));
                    }
                }
                """);
        Files.writeString(dir.resolve("CrateErrors.java"), """
                package com.example.e2e.crate;

                import org.springframework.http.HttpStatus;
                import org.springframework.http.ProblemDetail;
                import org.springframework.http.ResponseEntity;
                import org.springframework.http.server.reactive.ServerHttpResponse;
                import org.springframework.web.bind.annotation.ExceptionHandler;
                import org.springframework.web.bind.annotation.RestControllerAdvice;
                import reactor.core.publisher.Mono;

                @RestControllerAdvice
                public class CrateErrors {
                    @ExceptionHandler(Exception.class)
                    Mono<ResponseEntity<ProblemDetail>> unexpected(Exception e) {
                        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .body(ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR)));
                    }

                    @ExceptionHandler(CrateMissing.class)
                    Mono<ProblemDetail> missing(CrateMissing e) {
                        return Mono.just(ProblemDetail.forStatus(HttpStatus.NOT_FOUND));
                    }

                    @ExceptionHandler(CrateLate.class)
                    Mono<CrateAnswer> late(CrateLate e) {
                        return Mono.just(new CrateAnswer("late"));
                    }

                    @ExceptionHandler(CrateBlocked.class)
                    Mono<CrateAnswer> blocked(CrateBlocked e, ServerHttpResponse response) {
                        response.setStatusCode(HttpStatus.LOCKED);
                        return Mono.just(new CrateAnswer("blocked"));
                    }
                }
                """);
    };

    /** The e2e classpath without Spring MVC (spring-webmvc and Boot's webmvc modules): spring-webflux stays, as in a WebFlux-only service. */
    private static Path withoutWebMvc(Path classpath, Path out) throws IOException {
        List<String> all = Files.readAllLines(classpath);
        List<String> kept = all.stream().filter(l -> !l.contains("webmvc")).toList();
        assertThat(kept).as("the classpath had Spring MVC").hasSizeLessThan(all.size());
        assertThat(kept).as("and Spring WebFlux").anyMatch(l -> l.contains("spring-webflux"));
        Files.write(out, kept);
        return out;
    }

    /**
     * #102: the errors pack on a WebFlux-only service (spring-webflux without spring-webmvc: Spring Boot runs it as a
     * reactive web application). PROBLEM_DETAILS_DISABLED names spring.webflux.problemdetails.enabled and is satisfied by
     * it or by an advice extending WebFlux's ResponseEntityExceptionHandler (from the sources or a library); the catch-all
     * answering 500 takes the WebExchangeBindException of a failed @Valid (CLIENT_ERROR_AS_SERVER_ERROR, REQUEST_NOT_VALIDATED
     * name it; a handler for it settles it); ERROR_HANDLER_STATUS_OK looks into Mono&lt;T&gt; (a Mono&lt;ProblemDetail&gt;
     * carries its status, a Mono&lt;CrateAnswer&gt; does not) and leaves a handler that sets the status on the reactive
     * ServerHttpResponse alone; ERROR_RESPONSE_UNTRACED is a report (logging-support's ProblemTraceId is Spring MVC only),
     * so no support class is copied for it. Every file must equal e2e/expected-webflux-errors; the errors pack alone gives
     * the same findings; on the shared classpath (MVC and WebFlux) the MVC names stay.
     */
    @Test
    void errorsPackOnAWebFluxOnlyServiceUsesTheReactiveNames() throws IOException {
        String webflux = withoutWebMvc(Path.of(CLASSPATH), tmp.resolve("classpath-webflux.txt")).toString();
        Map<String, String> actual = migrate(tmp.resolve("app"), webflux, WEBFLUX_ERRORS);
        String findings = actual.get("FINDINGS.txt");
        assertWebFluxErrorFindings(findings);
        assertThat(actual.get("src/main/java/com/example/e2e/crate/CrateErrors.java")).as("the catch-all logs the exception")
                .contains("log.error(\"Request failed unexpectedly\", e);");
        Path support = tmp.resolve("app/src/main/java/com/example/e2e/logging");
        assertThat(support.resolve("ReactiveProblemTraceId.java")).as("WebFlux's ReactiveProblemTraceId copied (#103)").exists();
        assertThat(support.resolve("ProblemTraceId.java")).as("not the Spring MVC advice").doesNotExist();
        assertThat(actual.get("src/main/resources/application.yml")).contains("problem-trace-id:\n    enabled: true");
        golden(EXPECTED.resolveSibling("expected-webflux-errors"), actual);

        // the errors pack alone: the same findings, a compiling and settled result; the trace id class and both properties
        Map<String, String> alone = migrate(tmp.resolve("errors-only"), webflux, withPacks(WEBFLUX_ERRORS, "[errors]", ""));
        assertWebFluxErrorFindings(alone.get("FINDINGS.txt"));
        Path aloneSupport = tmp.resolve("errors-only/src/main/java/com/example/e2e/logging");
        assertThat(aloneSupport.resolve("ReactiveProblemTraceId.java")).exists();
        assertThat(aloneSupport.resolve("ProblemTraceId.java")).doesNotExist();
        assertThat(alone.get("src/main/resources/application.yml")).contains("problem-trace-id:\n    enabled: true");

        // what settles each finding on WebFlux: the property, WebFlux's ResponseEntityExceptionHandler, a handler for
        // WebExchangeBindException
        Path property = tmp.resolve("property");
        copy(BEFORE, property);
        WEBFLUX_ERRORS.run(property);
        Path yml = property.resolve("src/main/resources/application.yml");
        Files.writeString(yml, Files.readString(yml).replaceFirst("(?m)^spring:\n", "spring:\n  webflux:\n    problemdetails:\n      enabled: true\n"));
        assertThat(Files.readString(yml)).contains("problemdetails");
        assertThat(run(property, "scan", property.toString(), "--classpath-file", webflux)).doesNotContain("PROBLEM_DETAILS_DISABLED");

        Path advice = tmp.resolve("advice");
        copy(BEFORE, advice);
        WEBFLUX_ERRORS.run(advice);
        Files.writeString(advice.resolve("src/main/java/com/example/e2e/crate/CrateFrameworkErrors.java"), """
                package com.example.e2e.crate;

                import org.springframework.http.HttpStatus;
                import org.springframework.http.ProblemDetail;
                import org.springframework.web.bind.annotation.ExceptionHandler;
                import org.springframework.web.bind.annotation.RestControllerAdvice;
                import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler;

                @RestControllerAdvice
                public class CrateFrameworkErrors extends ResponseEntityExceptionHandler {
                    @ExceptionHandler(IllegalStateException.class)
                    ProblemDetail conflict(IllegalStateException e) {
                        return ProblemDetail.forStatus(HttpStatus.CONFLICT);
                    }
                }
                """);
        assertThat(run(advice, "scan", advice.toString(), "--classpath-file", webflux)).doesNotContain("PROBLEM_DETAILS_DISABLED")
                .doesNotContain("also takes the validation failures");

        Path mapped = tmp.resolve("mapped");
        copy(BEFORE, mapped);
        WEBFLUX_ERRORS.run(mapped);
        Files.writeString(mapped.resolve("src/main/java/com/example/e2e/crate/CrateValidationErrors.java"), """
                package com.example.e2e.crate;

                import org.springframework.http.HttpStatus;
                import org.springframework.http.ProblemDetail;
                import org.springframework.web.bind.annotation.ExceptionHandler;
                import org.springframework.web.bind.annotation.RestControllerAdvice;
                import org.springframework.web.bind.support.WebExchangeBindException;

                @RestControllerAdvice
                public class CrateValidationErrors {
                    @ExceptionHandler(WebExchangeBindException.class)
                    ProblemDetail invalid(WebExchangeBindException e) {
                        return ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
                    }
                }
                """);
        assertThat(run(mapped, "scan", mapped.toString(), "--classpath-file", webflux)).doesNotContain("also takes the validation failures");

        // Spring MVC and WebFlux both on the classpath: Spring Boot runs Spring MVC, the MVC names stay
        Path both = tmp.resolve("both");
        copy(BEFORE, both);
        WEBFLUX_ERRORS.run(both);
        assertThat(run(both, "scan", both.toString(), "--classpath-file", CLASSPATH))
                .contains("Set spring.mvc.problemdetails.enabled: true").doesNotContain("spring.webflux.problemdetails")
                .contains("Map MethodArgumentNotValidException to 400").doesNotContain("WebExchangeBindException");

        // a library's advice extending WebFlux's ResponseEntityExceptionHandler answers the framework errors too (#52)
        Path library = tmp.resolve("library");
        copy(BEFORE, library);
        WEBFLUX_ERRORS.run(library);
        Path libraryCp = withoutWebMvc(libraryClasspath("reactive-errors-library", Map.of(
                "org/orchardlib/reactive/PlatformErrors.java", """
                        package org.orchardlib.reactive;

                        import org.springframework.web.bind.annotation.RestControllerAdvice;
                        import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler;

                        @RestControllerAdvice
                        public class PlatformErrors extends ResponseEntityExceptionHandler {
                        }
                        """)), tmp.resolve("classpath-webflux-library.txt"));
        assertThat(run(library, "scan", library.toString(), "--classpath-file", libraryCp.toString()))
                .doesNotContain("PROBLEM_DETAILS_DISABLED").doesNotContain("also takes the validation failures");
    }

    private static void assertWebFluxErrorFindings(String findings) {
        assertThat(findings).as("WebFlux's property")
                .contains("Spring WebFlux errors (400 bad input, 405, 415, ...) are not RFC 9457 ProblemDetail responses")
                .contains("Set spring.webflux.problemdetails.enabled: true").doesNotContain("spring.mvc.problemdetails")
                .as("WebFlux's validation exception")
                .contains("Map WebExchangeBindException to 400").doesNotContain("MethodArgumentNotValidException")
                .contains("CrateController.relabel takes request")
                .as("a Mono<ProblemDetail> carries its status, a Mono<CrateAnswer> does not")
                .contains("Handler for CrateLate returns CrateAnswer without @ResponseStatus")
                .doesNotContain("Handler for CrateMissing").doesNotContain("returns Mono")
                .as("the status set on the reactive response").doesNotContain("Handler for CrateBlocked")
                .as("an exception signalled with Mono.error on an HTTP path is unmapped (#103)")
                .contains("CrateSealed is thrown on HTTP paths")
                .as("a non-blocking WebClient call fails into the handlers; one with onErrorResume does not (#103)")
                .contains("Failures of downstream calls (WebClient, 1× on HTTP paths, e.g. WebClient.ResponseSpec.bodyToMono in "
                        + "CrateController.label")
                .as("the trace id in error responses: switched on, not a report (#103)")
                .doesNotContain("Error responses do not carry the trace id");
    }

    /**
     * #51: API-first controllers. The request mappings sit on an interface the controller implements, the class-level
     * {@code @RequestMapping} too, and the paths come from {@code static final String} constants and their
     * concatenation: Spring finds mapping annotations through the type hierarchy, so these are HTTP entrypoints with
     * their real paths (traffic.yaml, audit.actions.http, the entry MDC). A non-controller implementing the same
     * interface stays no endpoint. Every file must equal e2e/expected-mapped-interface.
     */
    @Test
    void seesRequestMappingsOnInterfacesAndConstantPaths() throws IOException {
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, app -> {
            Path yml = app.resolve("logast.yml");
            Files.writeString(yml, Files.readString(yml).replace("vocabulary:\n", """
                    audit:
                      actions:
                        - http: "POST /lockers/*/open"
                    vocabulary:
                      locker:
                        mdc:
                          key: lockerId
                          sources:
                            params: [lockerId]
                    """));
            Path dir = app.resolve("src/main/java/com/example/e2e/locker");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("LockerPaths.java"), """
                    package com.example.e2e.locker;

                    public final class LockerPaths {
                        public static final String BASE = "/lockers";
                        public static final String LOCKER = "/{lockerId}";
                        public static final String OPEN = LOCKER + "/open";

                        private LockerPaths() {
                        }
                    }
                    """);
            Files.writeString(dir.resolve("LockerApi.java"), """
                    package com.example.e2e.locker;

                    import org.springframework.web.bind.annotation.GetMapping;
                    import org.springframework.web.bind.annotation.PathVariable;
                    import org.springframework.web.bind.annotation.PostMapping;
                    import org.springframework.web.bind.annotation.RequestMapping;

                    @RequestMapping(LockerPaths.BASE)
                    public interface LockerApi {
                        @GetMapping(LockerPaths.LOCKER)
                        String status(@PathVariable("lockerId") String lockerId);

                        @PostMapping(path = LockerPaths.OPEN)
                        String open(@PathVariable("lockerId") String lockerId);
                    }
                    """);
            Files.writeString(dir.resolve("LockerController.java"), """
                    package com.example.e2e.locker;

                    import org.slf4j.Logger;
                    import org.slf4j.LoggerFactory;
                    import org.springframework.web.bind.annotation.GetMapping;
                    import org.springframework.web.bind.annotation.RestController;

                    @RestController
                    public class LockerController implements LockerApi {
                        private static final Logger log = LoggerFactory.getLogger(LockerController.class);

                        @Override
                        public String status(String lockerId) {
                            log.debug("Locker status requested");
                            return "closed";
                        }

                        @Override
                        public String open(String lockerId) {
                            log.debug("Locker opened");
                            return "open";
                        }

                        @GetMapping("/" + "free")
                        public int free() {
                            return 3;
                        }
                    }
                    """);
            Files.writeString(dir.resolve("LockerStub.java"), """
                    package com.example.e2e.locker;

                    import org.springframework.stereotype.Component;

                    /** Implements the mapped interface without being a controller (a client stub): no endpoint. */
                    @Component
                    public class LockerStub implements LockerApi {
                        @Override
                        public String status(String lockerId) {
                            return "unknown";
                        }

                        @Override
                        public String open(String lockerId) {
                            return "unknown";
                        }
                    }
                    """);
        });

        String traffic = actual.get(".logast/decisions/traffic.yaml");
        assertThat(traffic).as("a class that is not a controller serves no mapping").doesNotContain("LockerStub")
                .as("the mapped interface methods are entrypoints, with the paths their constants spell")
                .contains("\"GET /lockers/{lockerId}\":").contains("\"POST /lockers/{lockerId}/open\":")
                .as("the interface's class-level mapping applies to the controller's own mapping")
                .contains("\"GET /lockers/free\":");
        golden(EXPECTED.resolveSibling("expected-mapped-interface"), actual);
    }

    /** #51: a library jar (published OpenAPI interfaces) with a mapped interface and a composed mapping annotation. */
    private static final Map<String, String> SHELF_LIBRARY = Map.of(
            "com/example/lib/shelf/ShelfApi.java", """
                    package com.example.lib.shelf;

                    import org.springframework.web.bind.annotation.PathVariable;
                    import org.springframework.web.bind.annotation.PostMapping;
                    import org.springframework.web.bind.annotation.RequestMapping;

                    @RequestMapping("/shelves")
                    public interface ShelfApi {
                        String SHELF = "/{shelfId}";

                        @ReadOnlyGet(SHELF)
                        String shelf(@PathVariable("shelfId") String shelfId);

                        @PostMapping(path = SHELF + "/restock")
                        String restock(@PathVariable("shelfId") String shelfId);
                    }
                    """,
            "com/example/lib/shelf/ApiController.java", """
                    package com.example.lib.shelf;

                    import java.lang.annotation.ElementType;
                    import java.lang.annotation.Retention;
                    import java.lang.annotation.RetentionPolicy;
                    import java.lang.annotation.Target;
                    import org.springframework.web.bind.annotation.RestController;

                    @Target(ElementType.TYPE)
                    @Retention(RetentionPolicy.RUNTIME)
                    @RestController
                    public @interface ApiController {
                    }
                    """,
            "com/example/lib/shelf/ReadOnlyGet.java", """
                    package com.example.lib.shelf;

                    import java.lang.annotation.ElementType;
                    import java.lang.annotation.Retention;
                    import java.lang.annotation.RetentionPolicy;
                    import java.lang.annotation.Target;
                    import org.springframework.core.annotation.AliasFor;
                    import org.springframework.web.bind.annotation.RequestMapping;
                    import org.springframework.web.bind.annotation.RequestMethod;

                    @Target(ElementType.METHOD)
                    @Retention(RetentionPolicy.RUNTIME)
                    @RequestMapping(method = RequestMethod.GET)
                    public @interface ReadOnlyGet {
                        @AliasFor(annotation = RequestMapping.class)
                        String[] value() default {};
                    }
                    """);

    /**
     * #51, the rest of it: a controller implementing a mapped interface from a library jar (read from the class
     * file, a composed mapping annotation there too; the controller marked through the jar's meta-annotation), one
     * implementing an interface in generated sources under build/generated (compiled with the sources for
     * its types, never analysed or edited), a controller marked through a source meta-annotation that also carries the
     * class-level mapping, composed mapping annotations from source and from the jar, and path arrays (every path is a
     * label: traffic.yaml lists each, an audit glob matching the second path counts). Every file must equal
     * e2e/expected-mapping-sources.
     */
    @Test
    void seesMappingsFromJarsGeneratedSourcesMetaAnnotationsAndPathArrays() throws IOException {
        String classpath = libraryClasspath("shelf-api", SHELF_LIBRARY).toString();
        String generatedBinApi = """
                package com.example.e2e.gen;

                import org.springframework.web.bind.annotation.DeleteMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.RequestMapping;

                @RequestMapping("/bins")
                public interface BinApi {
                    @DeleteMapping("/{binId}")
                    String empty(@PathVariable("binId") String binId);
                }
                """;
        Path[] generated = new Path[1];
        Map<String, String> actual = migrate(tmp.resolve("app"), classpath, app -> {
            Path yml = app.resolve("logast.yml");
            Files.writeString(yml, Files.readString(yml).replace("vocabulary:\n", """
                    audit:
                      actions:
                        - http: "POST /shelves/*/restock"
                        - http: "PUT /trays/by-label/*/seal"
                    vocabulary:
                    """));
            generated[0] = app.resolve("build/generated/openapi/src/main/java/com/example/e2e/gen/BinApi.java");
            Files.createDirectories(generated[0].getParent());
            Files.writeString(generated[0], generatedBinApi);
            Path dir = app.resolve("src/main/java/com/example/e2e/shelf");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("ShelfController.java"), """
                    package com.example.e2e.shelf;

                    import com.example.lib.shelf.ApiController;
                    import com.example.lib.shelf.ShelfApi;
                    import org.slf4j.Logger;
                    import org.slf4j.LoggerFactory;

                    @ApiController
                    public class ShelfController implements ShelfApi {
                        private static final Logger log = LoggerFactory.getLogger(ShelfController.class);

                        @Override
                        public String shelf(String shelfId) {
                            log.debug("Shelf requested");
                            return "shelf";
                        }

                        @Override
                        public String restock(String shelfId) {
                            log.debug("Shelf restocked");
                            return "restocked";
                        }
                    }
                    """);
            Files.writeString(dir.resolve("BinController.java"), """
                    package com.example.e2e.shelf;

                    import com.example.e2e.gen.BinApi;
                    import org.springframework.web.bind.annotation.RestController;

                    @RestController
                    public class BinController implements BinApi {
                        @Override
                        public String empty(String binId) {
                            return "emptied";
                        }
                    }
                    """);
            Files.writeString(dir.resolve("TrayEndpoint.java"), """
                    package com.example.e2e.shelf;

                    import java.lang.annotation.ElementType;
                    import java.lang.annotation.Retention;
                    import java.lang.annotation.RetentionPolicy;
                    import java.lang.annotation.Target;
                    import org.springframework.web.bind.annotation.RequestMapping;
                    import org.springframework.web.bind.annotation.RestController;

                    /** A team's controller annotation: a @RestController with its base path. */
                    @Target(ElementType.TYPE)
                    @Retention(RetentionPolicy.RUNTIME)
                    @RestController
                    @RequestMapping("/trays")
                    public @interface TrayEndpoint {
                    }
                    """);
            Files.writeString(dir.resolve("JsonGet.java"), """
                    package com.example.e2e.shelf;

                    import java.lang.annotation.ElementType;
                    import java.lang.annotation.Retention;
                    import java.lang.annotation.RetentionPolicy;
                    import java.lang.annotation.Target;
                    import org.springframework.core.annotation.AliasFor;
                    import org.springframework.web.bind.annotation.RequestMapping;
                    import org.springframework.web.bind.annotation.RequestMethod;

                    @Target(ElementType.METHOD)
                    @Retention(RetentionPolicy.RUNTIME)
                    @RequestMapping(method = RequestMethod.GET, produces = "application/json")
                    public @interface JsonGet {
                        @AliasFor(annotation = RequestMapping.class)
                        String[] value() default {};
                    }
                    """);
            Files.writeString(dir.resolve("TrayController.java"), """
                    package com.example.e2e.shelf;

                    import com.example.lib.shelf.ReadOnlyGet;
                    import org.springframework.web.bind.annotation.GetMapping;
                    import org.springframework.web.bind.annotation.PathVariable;
                    import org.springframework.web.bind.annotation.PutMapping;

                    @TrayEndpoint
                    public class TrayController {
                        @GetMapping({"/{trayId}", "/by-label/{label}"})
                        public String tray(@PathVariable(required = false) String trayId, @PathVariable(required = false) String label) {
                            return "tray";
                        }

                        @PutMapping(path = {"/{trayId}/seal", "/by-label/{label}/seal"})
                        public String seal(@PathVariable(required = false) String trayId, @PathVariable(required = false) String label) {
                            return "sealed";
                        }

                        @JsonGet("/count")
                        public int count() {
                            return 3;
                        }

                        @ReadOnlyGet("/latest")
                        public String latest() {
                            return "latest";
                        }
                    }
                    """);
        });

        assertThat(Files.readString(generated[0])).as("generated sources are never edited").isEqualTo(generatedBinApi);
        assertThat(actual.get("FINDINGS.txt")).as("generated sources are never reported").doesNotContain("build/generated");
        String traffic = actual.get(".logast/decisions/traffic.yaml");
        assertThat(traffic).as("mappings on an interface from a library jar, in a controller marked by the jar's annotation")
                .contains("\"GET /shelves/{shelfId}\":").contains("\"POST /shelves/{shelfId}/restock\":")
                .as("mappings on an interface in generated sources").contains("\"DELETE /bins/{binId}\":")
                .as("a controller through a meta-annotation, with its class-level mapping; every path of an array")
                .contains("\"GET /trays/{trayId}\":").contains("\"GET /trays/by-label/{label}\":")
                .contains("\"PUT /trays/{trayId}/seal\":").contains("\"PUT /trays/by-label/{label}/seal\":")
                .as("composed mapping annotations from source and from a jar")
                .contains("\"GET /trays/count\":").contains("\"GET /trays/latest\":");
        golden(EXPECTED.resolveSibling("expected-mapping-sources"), actual);
    }

    /**
     * #50: audit emitters, actor and target are recognised by type and value origin, not by name. A save of an entity
     * whose superclass is named *Audit* records nothing; the actor read from a {@code Principal} (directly, through
     * {@code getName()}, a local, a constructor or a builder) is the actor; a domain object named {@code user}, a
     * client-supplied header and a boolean named {@code paid} are not; recording the attempt first, with getters as
     * its arguments, records failures too. Every file must equal e2e/expected-audit-origin.
     */
    @Test
    void recognisesAuditEmittersActorAndTargetByTypeAndOrigin() throws IOException {
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, app -> {
            Path dir = app.resolve("src/main/java/com/example/e2e/box");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("AuditableRecord.java"), """
                    package com.example.e2e.box;

                    /** A base class named like audit (as AbstractAuditable): its subclasses are not audit records. */
                    public abstract class AuditableRecord {
                        private String changedBy;

                        public String changedBy() {
                            return changedBy;
                        }
                    }
                    """);
            Files.writeString(dir.resolve("Box.java"), """
                    package com.example.e2e.box;

                    public class Box extends AuditableRecord {
                        private final String id;

                        public Box(String id) {
                            this.id = id;
                        }

                        public String id() {
                            return id;
                        }
                    }
                    """);
            Files.writeString(dir.resolve("BoxKeeper.java"), """
                    package com.example.e2e.box;

                    public record BoxKeeper(String name) {
                    }
                    """);
            Files.writeString(dir.resolve("BoxStore.java"), """
                    package com.example.e2e.box;

                    import java.util.Map;
                    import java.util.concurrent.ConcurrentHashMap;
                    import org.springframework.stereotype.Component;

                    @Component
                    public class BoxStore {
                        private final Map<String, Box> boxes = new ConcurrentHashMap<>();

                        public void save(Box box) {
                            boxes.put(box.id(), box);
                        }

                        public void clear(String boxId) {
                            boxes.remove(boxId);
                        }

                        public BoxKeeper keeper(String boxId) {
                            return new BoxKeeper(boxId);
                        }

                        public boolean pay(String boxId) {
                            return boxes.containsKey(boxId);
                        }
                    }
                    """);
            Files.writeString(dir.resolve("BoxAuditEntry.java"), """
                    package com.example.e2e.box;

                    public final class BoxAuditEntry {
                        private final String action;
                        private final String boxId;
                        private final String by;

                        public BoxAuditEntry(String action, String boxId, String by) {
                            this.action = action;
                            this.boxId = boxId;
                            this.by = by;
                        }

                        public static BoxAuditEntryBuilder builder() {
                            return new BoxAuditEntryBuilder();
                        }

                        @Override
                        public String toString() {
                            return by + " " + action + " " + boxId;
                        }

                        /** Named as Lombok names it: its steps are no audit records. */
                        public static final class BoxAuditEntryBuilder {
                            private String action;
                            private String boxId;
                            private String by;

                            public BoxAuditEntryBuilder action(String action) {
                                this.action = action;
                                return this;
                            }

                            public BoxAuditEntryBuilder box(String boxId) {
                                this.boxId = boxId;
                                return this;
                            }

                            public BoxAuditEntryBuilder by(String by) {
                                this.by = by;
                                return this;
                            }

                            public BoxAuditEntry build() {
                                return new BoxAuditEntry(action, boxId, by);
                            }
                        }
                    }
                    """);
            Files.writeString(dir.resolve("BoxAudit.java"), """
                    package com.example.e2e.box;

                    import java.util.List;
                    import java.util.concurrent.CopyOnWriteArrayList;
                    import org.springframework.stereotype.Component;

                    /** Writes audit records (here: in memory). */
                    @Component
                    public class BoxAudit {
                        private final List<String> entries = new CopyOnWriteArrayList<>();

                        public void record(String action, Object target, Object by) {
                            entries.add(by + " " + action + " " + target);
                        }

                        public void record(BoxAuditEntry entry) {
                            entries.add(entry.toString());
                        }
                    }
                    """);
            Files.writeString(dir.resolve("BoxController.java"), """
                    package com.example.e2e.box;

                    import java.security.Principal;
                    import org.springframework.web.bind.annotation.DeleteMapping;
                    import org.springframework.web.bind.annotation.PathVariable;
                    import org.springframework.web.bind.annotation.PostMapping;
                    import org.springframework.web.bind.annotation.RequestHeader;
                    import org.springframework.web.bind.annotation.RestController;

                    @RestController
                    public class BoxController {
                        private final BoxStore store;
                        private final BoxAudit audit;

                        public BoxController(BoxStore store, BoxAudit audit) {
                            this.store = store;
                            this.audit = audit;
                        }

                        /** Saves an entity whose superclass is named like audit: no audit record. */
                        @DeleteMapping("/boxes/{boxId}")
                        public void remove(@PathVariable String boxId) {
                            store.save(new Box(boxId));
                        }

                        /** Records the attempt first, the actor through a getter: failures are recorded too. */
                        @DeleteMapping("/boxes/{boxId}/contents")
                        public void empty(@PathVariable String boxId, Principal principal) {
                            audit.record("box.empty", boxId, principal.getName());
                            store.clear(boxId);
                        }

                        /** The box's keeper, a domain object named like the actor: not who did it. */
                        @PostMapping("/boxes/{boxId}/handover")
                        public void handover(@PathVariable String boxId) {
                            BoxKeeper user = store.keeper(boxId);
                            audit.record("box.handover", boxId, user);
                        }

                        /** The acting user as the client sends it: anyone can claim to be anyone. */
                        @PostMapping("/boxes/{boxId}/lock")
                        public void lock(@PathVariable String boxId, @RequestHeader("X-User") String user) {
                            audit.record("box.lock", boxId, user);
                        }

                        /** A flag named like an id (paid): not the record acted on. */
                        @PostMapping("/boxes/{boxId}/pay")
                        public void pay(@PathVariable String boxId, Principal principal) {
                            boolean paid = store.pay(boxId);
                            audit.record("box.pay", paid, principal);
                        }

                        /** The actor through a local read from the principal. */
                        @PostMapping("/boxes/{boxId}/label")
                        public void label(@PathVariable String boxId, Principal principal) {
                            String who = principal.getName();
                            audit.record("box.label", boxId, who);
                        }

                        /** One record built by a constructor. */
                        @PostMapping("/boxes/{boxId}/open")
                        public void open(@PathVariable String boxId, Principal principal) {
                            audit.record(new BoxAuditEntry("box.open", boxId, principal.getName()));
                        }

                        /** One record built by a builder chain. */
                        @PostMapping("/boxes/{boxId}/close")
                        public void close(@PathVariable String boxId, Principal principal) {
                            audit.record(BoxAuditEntry.builder().action("box.close").box(boxId).by(principal.getName()).build());
                        }
                    }
                    """);
        });

        String findings = actual.get("FINDINGS.txt");
        assertThat(findings).as("a save of an entity whose superclass is named *Audit* is no audit record")
                .contains("DELETE /boxes/{boxId} is an auditable action");
        assertThat(findings).as("recording the attempt first, with a getter as argument, records failures too")
                .doesNotContain("DELETE /boxes/{boxId}/contents records an audit event only when it succeeds");
        assertThat(findings).as("the actor from a Principal: through getName(), a local, a constructor, a builder")
                .doesNotContain("in BoxController.empty").doesNotContain("in BoxController.label")
                .doesNotContain("in BoxController.open").doesNotContain("in BoxController.close");
        assertThat(findings).as("a domain object named user is not the actor")
                .contains("The audit record record(…) in BoxController.handover does not say who did it");
        assertThat(findings).as("a header names the actor the client claims to be")
                .contains("The audit record record(…) in BoxController.lock takes the acting user from the request");
        assertThat(findings).as("a boolean named paid is no record id")
                .contains("The audit record record(…) in BoxController.pay does not say to which record");
        assertThat(findings).as("a @Scheduled path has no user: no actor asked for")
                .doesNotContain("The audit record audit(…) in EdgeFlow.process");
        golden(EXPECTED.resolveSibling("expected-audit-origin"), actual);
    }

    /**
     * Stand-ins for Spring Security, Spring Boot Actuator's audit types and Spring Data JPA, which are not on the e2e
     * classpath (adding them would change every scenario): only the types and members the audit scenario uses.
     */
    private static final Map<String, String> AUDIT_LIBRARY_STUBS = Map.of(
            "org/springframework/security/authentication/event/AbstractAuthenticationEvent.java", """
                    package org.springframework.security.authentication.event;

                    public abstract class AbstractAuthenticationEvent extends org.springframework.context.ApplicationEvent {
                        protected AbstractAuthenticationEvent(Object source) {
                            super(source);
                        }
                    }
                    """,
            "org/springframework/security/authorization/event/AuthorizationEvent.java", """
                    package org.springframework.security.authorization.event;

                    public class AuthorizationEvent extends org.springframework.context.ApplicationEvent {
                        public AuthorizationEvent(Object source) {
                            super(source);
                        }
                    }
                    """,
            "org/springframework/security/authorization/event/AuthorizationDeniedEvent.java", """
                    package org.springframework.security.authorization.event;

                    public class AuthorizationDeniedEvent extends AuthorizationEvent {
                        public AuthorizationDeniedEvent(Object source) {
                            super(source);
                        }
                    }
                    """,
            "org/springframework/security/authorization/AuthorizationEventPublisher.java", """
                    package org.springframework.security.authorization;

                    public interface AuthorizationEventPublisher {
                    }
                    """,
            "org/springframework/boot/actuate/audit/AuditEventRepository.java", """
                    package org.springframework.boot.actuate.audit;

                    public interface AuditEventRepository {
                    }
                    """,
            "org/springframework/boot/actuate/audit/InMemoryAuditEventRepository.java", """
                    package org.springframework.boot.actuate.audit;

                    public class InMemoryAuditEventRepository implements AuditEventRepository {
                    }
                    """,
            "org/springframework/data/jpa/repository/JpaRepository.java", """
                    package org.springframework.data.jpa.repository;

                    public interface JpaRepository<T, ID> extends org.springframework.data.repository.ListCrudRepository<T, ID> {
                    }
                    """,
            "org/springframework/data/jpa/repository/Modifying.java", """
                    package org.springframework.data.jpa.repository;

                    import java.lang.annotation.Retention;
                    import java.lang.annotation.RetentionPolicy;

                    @Retention(RetentionPolicy.RUNTIME)
                    public @interface Modifying {
                    }
                    """,
            "org/springframework/data/jpa/repository/Query.java", """
                    package org.springframework.data.jpa.repository;

                    import java.lang.annotation.Retention;
                    import java.lang.annotation.RetentionPolicy;

                    @Retention(RetentionPolicy.RUNTIME)
                    public @interface Query {
                        String value() default "";
                    }
                    """);

    /** The stubs compiled into a jar, and a classpath file of the e2e classpath plus that jar. */
    private Path auditLibraryClasspath() throws IOException {
        Path src = tmp.resolve("audit-stubs/src");
        Path out = Files.createDirectories(tmp.resolve("audit-stubs/classes"));
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "-cp",
                String.join(java.io.File.pathSeparator, Files.readAllLines(Path.of(CLASSPATH)).stream().filter(l -> !l.isBlank()).toList())));
        Map<String, String> stubs = new TreeMap<>(AUDIT_LIBRARY_STUBS);
        stubs.putAll(SECURITY_CONTEXT_STUBS);
        for (Map.Entry<String, String> e : stubs.entrySet()) {
            Path f = src.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
            args.add(f.toString());
        }
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, err, args.toArray(String[]::new)))
                .as("the audit library stubs compile:\n" + err).isZero();
        // a jar, as libraries come: logast leaves class folders out of type attribution
        Path jar = tmp.resolve("audit-stubs/audit-libraries.jar");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar)); Stream<Path> s = Files.walk(out)) {
            for (Path c : s.filter(Files::isRegularFile).sorted().toList()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(out.relativize(c).toString().replace('\\', '/')));
                zip.write(Files.readAllBytes(c));
                zip.closeEntry();
            }
        }
        extraClasses = new Path[]{jar};
        Path cp = tmp.resolve("classpath-audit-libraries.txt");
        List<String> lines = new ArrayList<>(Files.readAllLines(Path.of(CLASSPATH)));
        lines.add(jar.toString());
        Files.write(cp, lines);
        return cp;
    }

    private static final IoAction SHELF_AUDIT = app -> {
        Path dir = app.resolve("src/main/java/com/example/e2e/shelf");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("ShelfSlot.java"), """
                package com.example.e2e.shelf;

                import org.springframework.data.annotation.Id;
                import org.springframework.data.annotation.LastModifiedBy;

                /** Audited: Spring Data fills lastModifiedBy when a slot is saved (table shelf_slot). */
                public class ShelfSlot {
                    @Id
                    private String id;
                    private String status;
                    @LastModifiedBy
                    private String lastModifiedBy;
                }
                """);
        Files.writeString(dir.resolve("ShelfSlotRepository.java"), """
                package com.example.e2e.shelf;

                import org.springframework.data.jpa.repository.JpaRepository;
                import org.springframework.data.jpa.repository.Modifying;
                import org.springframework.data.jpa.repository.Query;

                public interface ShelfSlotRepository extends JpaRepository<ShelfSlot, String> {
                    /** A bulk update: the auditing listener does not run, lastModifiedBy stays as it was. */
                    @Modifying
                    @Query("update ShelfSlot s set s.status = :status where s.id = :id")
                    int markStatus(String id, String status);

                    /** Sets the editor itself: audited. */
                    @Modifying
                    @Query("update ShelfSlot s set s.status = :status, s.lastModifiedBy = :by where s.id = :id")
                    int markStatusBy(String id, String status, String by);

                    /** A delete: no row left to say who changed it last. */
                    @Modifying
                    @Query("delete from ShelfSlot s where s.status = 'EMPTY'")
                    int purgeEmpty();
                }
                """);
        Files.writeString(dir.resolve("ShelfSlotWriter.java"), """
                package com.example.e2e.shelf;

                import org.springframework.jdbc.core.JdbcTemplate;
                import org.springframework.jdbc.core.simple.JdbcClient;
                import org.springframework.stereotype.Repository;

                @Repository
                public class ShelfSlotWriter {
                    private static final String ADD_SLOT = "insert into shelf_slot (id, status) values (?, ?)";

                    private final JdbcTemplate jdbc;
                    private final JdbcClient client;

                    public ShelfSlotWriter(JdbcTemplate jdbc, JdbcClient client) {
                        this.jdbc = jdbc;
                        this.client = client;
                    }

                    public int block(String slotId) {
                        return jdbc.update("update shelf_slot set status = 'BLOCKED' where id = ?", slotId);
                    }

                    public int release(String slotId, String by) {
                        return jdbc.update("update shelf_slot set status = 'FREE', last_modified_by = ? where id = ?", by, slotId);
                    }

                    public int add(String slotId) {
                        return client.sql(ADD_SLOT).param(slotId).param("FREE").update();
                    }
                }
                """);
        Files.writeString(dir.resolve("ShelfAuditing.java"), """
                package com.example.e2e.shelf;

                import java.util.Optional;
                import org.springframework.boot.actuate.audit.InMemoryAuditEventRepository;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.context.annotation.Profile;
                import org.springframework.data.domain.AuditorAware;

                @Configuration
                public class ShelfAuditing {
                    @Bean
                    AuditorAware<String> shelfAuditor() {
                        return () -> Optional.of("system");
                    }

                    /** Boot records logins and denied access here: in memory, bounded, gone on restart. */
                    @Bean
                    InMemoryAuditEventRepository auditEvents() {
                        return new InMemoryAuditEventRepository();
                    }

                    /** Only for local runs: what Boot recommends it for. */
                    @Bean
                    @Profile("local")
                    InMemoryAuditEventRepository localAuditEvents() {
                        return new InMemoryAuditEventRepository();
                    }
                }
                """);
        Files.writeString(dir.resolve("ShelfAccessWatch.java"), """
                package com.example.e2e.shelf;

                import java.util.concurrent.atomic.AtomicLong;
                import org.springframework.context.event.EventListener;
                import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
                import org.springframework.stereotype.Component;

                /** Counts denied access, but nothing publishes authorization events. */
                @Component
                public class ShelfAccessWatch {
                    private final AtomicLong denied = new AtomicLong();

                    @EventListener
                    public void onDenied(AuthorizationDeniedEvent event) {
                        denied.incrementAndGet();
                    }
                }
                """);
    };

    /**
     * #92: the audit pack's deeper checks. Security events kept in Boot's InMemoryAuditEventRepository (development
     * only, per the Boot reference) are reported, the bean under a local profile is not; a listener for authorization
     * events with no AuthorizationEventPublisher bean is reported. Bulk writes on an audited entity's table bypass
     * Spring Data auditing: a {@code @Modifying @Query} update, a JdbcTemplate update and a JdbcClient insert (SQL from a
     * constant) are reported; the statements that set the editor column, and the delete, are not. Spring Security,
     * Actuator and JPA are stand-ins on this scenario's own classpath. The same checks with only the audit pack.
     * Every file must equal e2e/expected-audit-pack.
     */
    @Test
    void auditPackFindsInMemorySecurityEventsUnpublishedDenialsAndBulkWrites() throws IOException {
        String classpath = auditLibraryClasspath().toString();
        Map<String, String> actual = migrate(tmp.resolve("app"), classpath, SHELF_AUDIT);
        String findings = actual.get("FINDINGS.txt");
        assertThat(findings).as("an in-memory repository outside a dev profile")
                .contains("Logins and denied access are recorded in an InMemoryAuditEventRepository (@Bean ShelfAuditing.auditEvents)");
        assertThat(findings).as("a listener for denied access, nothing publishes")
                .contains("ShelfAccessWatch.onDenied listens for authorization events, but no AuthorizationEventPublisher bean");
        assertThat(findings).as("bulk writes on shelf_slot")
                .contains("ShelfSlotRepository.markStatus (@Modifying @Query) changes ShelfSlot rows")
                .contains("JdbcTemplate.update in ShelfSlotWriter.block changes ShelfSlot rows")
                .contains("JdbcClient.sql in ShelfSlotWriter.add changes ShelfSlot rows")
                .as("the statement sets the editor; a delete").doesNotContain("markStatusBy").doesNotContain("ShelfSlotWriter.release changes")
                .doesNotContain("purgeEmpty");
        golden(EXPECTED.resolveSibling("expected-audit-pack"), actual);

        // the audit pack alone: the same findings
        Map<String, String> alone = migrate(tmp.resolve("audit-only"), classpath, app -> {
            SHELF_AUDIT.run(app);
            Path yml = app.resolve("logast.yml");
            Files.writeString(yml, Files.readString(yml).replaceFirst("(?m)^packs: .*$", "packs: [audit]"));
        });
        assertThat(alone.get("FINDINGS.txt"))
                .contains("Logins and denied access are recorded in an InMemoryAuditEventRepository (@Bean ShelfAuditing.auditEvents)")
                .contains("ShelfAccessWatch.onDenied listens for authorization events, but no AuthorizationEventPublisher bean")
                .contains("ShelfSlotRepository.markStatus (@Modifying @Query) changes ShelfSlot rows")
                .contains("JdbcTemplate.update in ShelfSlotWriter.block changes ShelfSlot rows")
                .contains("JdbcClient.sql in ShelfSlotWriter.add changes ShelfSlot rows")
                .doesNotContain("markStatusBy").doesNotContain("ShelfSlotWriter.release changes").doesNotContain("purgeEmpty");
    }

    /** Stand-ins for Spring Security's SecurityContextHolder, which the badge scenario reads the acting user from. */
    private static final Map<String, String> SECURITY_CONTEXT_STUBS = Map.of(
            "org/springframework/security/core/Authentication.java", """
                    package org.springframework.security.core;

                    public interface Authentication extends java.security.Principal {
                    }
                    """,
            "org/springframework/security/core/context/SecurityContext.java", """
                    package org.springframework.security.core.context;

                    public interface SecurityContext {
                        org.springframework.security.core.Authentication getAuthentication();
                    }
                    """,
            "org/springframework/security/core/context/SecurityContextHolder.java", """
                    package org.springframework.security.core.context;

                    public final class SecurityContextHolder {
                        private SecurityContextHolder() {
                        }

                        public static SecurityContext getContext() {
                            return null;
                        }
                    }
                    """);

    private static final IoAction BADGE_AUDIT = app -> {
        Path dir = app.resolve("src/main/java/com/example/e2e/badge");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("BadgeAuditLog.java"), """
                package com.example.e2e.badge;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.stereotype.Component;

                /** Writes audit records as log lines on the audit logger: complete, and out the moment they are written. */
                @Component
                public class BadgeAuditLog {
                    private static final Logger AUDIT = LoggerFactory.getLogger("audit");

                    public void record(String action, String badgeId, String actor, String outcome) {
                        AUDIT.info("Badge audit record [action={}, badgeId={}, actor={}, outcome={}]", action, badgeId, actor, outcome);
                    }
                }
                """);
        Files.writeString(dir.resolve("BadgeAuditStore.java"), """
                package com.example.e2e.badge;

                import java.util.List;
                import java.util.concurrent.CopyOnWriteArrayList;
                import org.slf4j.MDC;
                import org.springframework.security.core.context.SecurityContextHolder;
                import org.springframework.stereotype.Component;

                /** Writes audit records to a store (here: in memory). */
                @Component
                public class BadgeAuditStore {
                    private final List<String> entries = new CopyOnWriteArrayList<>();

                    /** Neither the outcome nor the request it belongs to. */
                    public void note(String action, String badgeId, String actor) {
                        entries.add(actor + " " + action + " " + badgeId);
                    }

                    /** Complete: outcome and trace id passed in. */
                    public void record(String action, Object details, String actor, String outcome, String traceId) {
                        entries.add(traceId + " " + actor + " " + action + " " + details + " " + outcome);
                    }

                    /** Complete, reading the actor and the trace id itself. */
                    public void recordCurrent(String action, String badgeId, String outcome) {
                        String actor = SecurityContextHolder.getContext().getAuthentication().getName();
                        entries.add(MDC.get("traceId") + " " + actor + " " + action + " " + badgeId + " " + outcome);
                    }
                }
                """);
        Files.writeString(dir.resolve("BadgeRequest.java"), """
                package com.example.e2e.badge;

                /** What the client sends to issue a badge, its PIN included. */
                public record BadgeRequest(String badgeId, String holder, String pin) {
                }
                """);
        Files.writeString(dir.resolve("BadgeService.java"), """
                package com.example.e2e.badge;

                import java.util.Map;
                import java.util.concurrent.ConcurrentHashMap;
                import org.slf4j.MDC;
                import org.springframework.scheduling.annotation.Async;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;

                @Service
                public class BadgeService {
                    private final Map<String, String> badges = new ConcurrentHashMap<>();
                    private final BadgeAuditLog auditLog;
                    private final BadgeAuditStore auditStore;

                    public BadgeService(BadgeAuditLog auditLog, BadgeAuditStore auditStore) {
                        this.auditLog = auditLog;
                        this.auditStore = auditStore;
                    }

                    /** The record is a log line, written before the transaction commits. */
                    @Transactional
                    public void revoke(String badgeId, String actor) {
                        badges.remove(badgeId);
                        auditLog.record("badge.revoke", badgeId, actor, "done");
                    }

                    /** The failure is recorded in the catch: the transaction rolls back and the record says so. */
                    @Transactional
                    public void suspend(String badgeId, String actor) {
                        try {
                            badges.put(badgeId, "SUSPENDED");
                        } catch (RuntimeException e) {
                            auditLog.record("badge.suspend", badgeId, actor, "failed");
                            throw e;
                        }
                    }

                    /** A record in the store, not a log line: logast cannot tell it leaves the transaction. */
                    @Transactional
                    public void renew(String badgeId, String actor) {
                        badges.put(badgeId, "ACTIVE");
                        auditStore.record("badge.renew", badgeId, actor, "done", MDC.get("traceId"));
                    }

                    /** Neither outcome nor trace id in the record. */
                    public void lock(String badgeId, String actor) {
                        badges.put(badgeId, "LOCKED");
                        auditStore.note("badge.lock", badgeId, actor);
                    }

                    /** The new PIN and the request holding the PIN go into the record; the token id does not leak it. */
                    public void changePin(String badgeId, String newPin, String tokenId, BadgeRequest request, String actor) {
                        badges.put(badgeId, newPin);
                        auditStore.record("badge.pin", newPin, actor, "done", MDC.get("traceId"));
                        auditStore.record("badge.issue", request, actor, "done", MDC.get("traceId"));
                        auditStore.record("badge.token", tokenId, actor, "done", MDC.get("traceId"));
                    }

                    /** On another thread: the store reads the security context there, which is empty. */
                    @Async
                    public void expire(String badgeId) {
                        badges.remove(badgeId);
                        auditStore.recordCurrent("badge.expire", badgeId, "done");
                    }

                    /** On another thread, with the actor read on the request thread and passed in. */
                    @Async
                    public void archive(String badgeId, String actor) {
                        badges.remove(badgeId);
                        auditStore.record("badge.archive", badgeId, actor, "done", MDC.get("traceId"));
                    }
                }
                """);
        Files.writeString(dir.resolve("BadgeController.java"), """
                package com.example.e2e.badge;

                import java.security.Principal;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RequestParam;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class BadgeController {
                    private final BadgeService badges;

                    public BadgeController(BadgeService badges) {
                        this.badges = badges;
                    }

                    @PostMapping("/badges/{badgeId}/revoke")
                    public void revoke(@PathVariable String badgeId, Principal principal) {
                        badges.revoke(badgeId, principal.getName());
                    }

                    @PostMapping("/badges/{badgeId}/suspend")
                    public void suspend(@PathVariable String badgeId, Principal principal) {
                        badges.suspend(badgeId, principal.getName());
                    }

                    @PostMapping("/badges/{badgeId}/renew")
                    public void renew(@PathVariable String badgeId, Principal principal) {
                        badges.renew(badgeId, principal.getName());
                    }

                    @PostMapping("/badges/{badgeId}/lock")
                    public void lock(@PathVariable String badgeId, Principal principal) {
                        badges.lock(badgeId, principal.getName());
                    }

                    @PostMapping("/badges/{badgeId}/pin")
                    public void changePin(@PathVariable String badgeId, @RequestParam String newPin, @RequestParam String tokenId,
                                          @RequestBody BadgeRequest request, Principal principal) {
                        badges.changePin(badgeId, newPin, tokenId, request, principal.getName());
                    }

                    @PostMapping("/badges/{badgeId}/expire")
                    public void expire(@PathVariable String badgeId) {
                        badges.expire(badgeId);
                    }

                    @PostMapping("/badges/{badgeId}/archive")
                    public void archive(@PathVariable String badgeId, Principal principal) {
                        badges.archive(badgeId, principal.getName());
                    }
                }
                """);
        Path resources = app.resolve("src/main/resources");
        // the service's own logback file (a file appender, so the logging pack keeps it): no logger for "audit"
        Files.writeString(resources.resolve("logback-spring.xml"), """
                <configuration>
                    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
                        <file>logs/service.log</file>
                        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
                            <fileNamePattern>logs/service.%d.log</fileNamePattern>
                        </rollingPolicy>
                        <encoder>
                            <pattern>%d %level %logger %msg%n</pattern>
                        </encoder>
                    </appender>
                    <root level="INFO">
                        <appender-ref ref="FILE"/>
                    </root>
                </configuration>
                """);
        // the records are written at INFO
        Files.writeString(resources.resolve("application.properties"), "logging.level.audit=WARN\n");
    };

    /**
     * #92: complete audit records, routed and safe. An audit method whose records hold neither an outcome nor a trace
     * id is reported once, at the method; the ones that take both, or read them, are not. A record written as a log
     * line inside a transaction is reported (it goes out before the commit); the failure recorded in a catch and the
     * record in a store are not. The logback file without a logger for "audit", and logging.level.audit=WARN above the
     * records' INFO, are reported. A PIN, and a request holding one, passed to the audit call are reported; a token id is
     * not. The security context read on an @Async thread is reported; the actor read before the hand-over is not.
     * Spring Security's context types are stand-ins on this scenario's classpath. The same with only the audit pack.
     * Every file must equal e2e/expected-audit-records.
     */
    @Test
    void auditPackFindsIncompleteUnroutedUncommittedSecretAndAsyncRecords() throws IOException {
        String classpath = auditLibraryClasspath().toString();
        Map<String, String> actual = migrate(tmp.resolve("app"), classpath, BADGE_AUDIT);
        assertBadgeFindings(actual.get("FINDINGS.txt"));
        golden(EXPECTED.resolveSibling("expected-audit-records"), actual);

        Map<String, String> alone = migrate(tmp.resolve("audit-only"), classpath, app -> {
            BADGE_AUDIT.run(app);
            Path yml = app.resolve("logast.yml");
            Files.writeString(yml, Files.readString(yml).replaceFirst("(?m)^packs: .*$", "packs: [audit]"));
        });
        assertBadgeFindings(alone.get("FINDINGS.txt"));
    }

    private static void assertBadgeFindings(String findings) {
        assertThat(findings).as("item 2: no outcome, no trace id")
                .contains("The audit records BadgeAuditStore.note writes (1 call) do not say whether the action succeeded, "
                        + "nor which request they belong to")
                .as("both passed in, or read").doesNotContain("records BadgeAuditStore.record ")
                .doesNotContain("records BadgeAuditStore.recordCurrent").doesNotContain("records BadgeAuditLog.record");
        assertThat(findings).as("item 5: a log line inside the transaction")
                .contains("The audit record record(…) in BadgeService.revoke is written inside the transaction of BadgeService.revoke")
                .as("the failure in a catch; a store").doesNotContain("in BadgeService.suspend is written inside")
                .doesNotContain("in BadgeService.renew is written inside");
        assertThat(findings).as("item 6: no logger for audit in logback-spring.xml; its level above the records")
                .contains("logback-spring.xml has no <logger name=\"audit\">")
                .contains("logging.level.audit is WARN, but audit records are written at INFO");
        assertThat(findings).as("item 7: a PIN, a request holding one; not a token id")
                .contains("in BadgeService.changePin holds a secret: newPin")
                .contains("holds a secret: request (its pin)")
                .doesNotContain("holds a secret: tokenId");
        assertThat(findings).as("item 3: the security context read on an @Async thread; not the actor passed in")
                .contains("The audit record recordCurrent(…) in BadgeService.expire reads the acting user from the security "
                        + "context on another thread")
                .doesNotContain("in BadgeService.archive reads the acting user");
    }

    /**
     * #53: a fix logs through the logger in scope: an inherited or enclosing SLF4J logger, or Lombok's field under the
     * name lombok.config gives it. A class logging through another API (Lombok's @Log4j2, a java.util.logging field)
     * gets no SLF4J edits, and the result is the same with and without Lombok on logast's classpath.
     */
    @Test
    void fixesUseTheLoggerInScopeAndLeaveOtherLoggingApisAlone() throws IOException {
        IoAction prepare = app -> {
            Path pkg = app.resolve("src/main/java/com/example/e2e/loggers");
            Files.createDirectories(pkg.resolve("renamed"));
            Files.writeString(pkg.resolve("BaseJob.java"), """
                    package com.example.e2e.loggers;

                    import org.slf4j.Logger;
                    import org.slf4j.LoggerFactory;

                    abstract class BaseJob {
                        protected final Logger log = LoggerFactory.getLogger(getClass());

                        abstract void run(Runnable step);
                    }
                    """);
            Files.writeString(pkg.resolve("CleanupJob.java"), """
                    package com.example.e2e.loggers;

                    class CleanupJob extends BaseJob {
                        @Override
                        void run(Runnable step) {
                            try {
                                step.run();
                            } catch (IllegalStateException e) {
                            }
                        }
                    }
                    """);
            Files.writeString(pkg.resolve("Dispatcher.java"), """
                    package com.example.e2e.loggers;

                    import org.slf4j.Logger;
                    import org.slf4j.LoggerFactory;

                    class Dispatcher {
                        private static final Logger LOGGER = LoggerFactory.getLogger(Dispatcher.class);

                        class Worker {
                            void run(Runnable step) {
                                try {
                                    step.run();
                                } catch (IllegalStateException e) {
                                }
                            }
                        }
                    }
                    """);
            Files.writeString(pkg.resolve("ArchiveJob.java"), """
                    package com.example.e2e.loggers;

                    import lombok.extern.log4j.Log4j2;

                    @Log4j2
                    class ArchiveJob {
                        void run(Runnable step) {
                            try {
                                step.run();
                            } catch (IllegalStateException e) {
                                log.warn("Archive step failed", e);
                            }
                        }

                        void retry(Runnable step) {
                            try {
                                step.run();
                            } catch (IllegalArgumentException e) {
                            }
                        }
                    }
                    """);
            Files.writeString(pkg.resolve("LegacyJob.java"), """
                    package com.example.e2e.loggers;

                    import java.util.logging.Logger;

                    class LegacyJob {
                        private static final Logger log = Logger.getLogger(LegacyJob.class.getName());

                        void run(Runnable step) {
                            try {
                                step.run();
                            } catch (IllegalStateException e) {
                            }
                        }
                    }
                    """);
            Files.writeString(pkg.resolve("renamed/lombok.config"), "lombok.log.fieldName = LOG\n");
            Files.writeString(pkg.resolve("renamed/PurgeJob.java"), """
                    package com.example.e2e.loggers.renamed;

                    import lombok.extern.slf4j.Slf4j;

                    @Slf4j
                    class PurgeJob {
                        void run(Runnable step) {
                            LOG.debug("Purge started");
                            try {
                                step.run();
                            } catch (IllegalStateException e) {
                            }
                        }
                    }
                    """);
        };
        Map<String, String> actual = migrateAndFillLoggerTexts(tmp.resolve("app"), CLASSPATH, prepare);

        String dir = "src/main/java/com/example/e2e/loggers/";
        assertThat(actual.get(dir + "CleanupJob.java")).as("the inherited logger").contains("log.warn(")
                .doesNotContain("Slf4j").doesNotContain("LoggerFactory");
        assertThat(actual.get(dir + "Dispatcher.java")).as("the enclosing class's logger").contains("LOGGER.warn(")
                .doesNotContain("Slf4j").containsOnlyOnce("LoggerFactory.getLogger");
        assertThat(actual.get(dir + "renamed/PurgeJob.java")).as("lombok.log.fieldName").contains("LOG.warn(")
                .doesNotContain(" log.");
        assertThat(actual.get(dir + "ArchiveJob.java")).as("a Log4j2 class gets no SLF4J edit")
                .doesNotContain("Slf4j").doesNotContain("org.slf4j").containsOnlyOnce("log.warn(");
        assertThat(actual.get(dir + "LegacyJob.java")).as("a java.util.logging class gets no SLF4J edit")
                .doesNotContain("Slf4j").doesNotContain("org.slf4j").doesNotContain("log.warn(");
        golden(EXPECTED.resolveSibling("expected-logger-in-scope"), actual);

        // the same without Lombok on logast's classpath (the service still compiles with it)
        List<String> cp = Files.readAllLines(Path.of(CLASSPATH)).stream().filter(l -> !l.contains("lombok")).toList();
        Path cpFile = tmp.resolve("classpath-nolombok.txt");
        Files.write(cpFile, cp);
        Map<String, String> withoutLombok = new TreeMap<>(migrateAndFillLoggerTexts(tmp.resolve("nolombok"), cpFile.toString(), prepare));
        withoutLombok.put("OUTPUT.txt", withoutLombok.get("OUTPUT.txt").lines().filter(l -> !l.contains("recognised by name only"))
                .collect(java.util.stream.Collectors.joining("\n", "", "\n")));
        assertSameFiles(actual, withoutLombok);
    }

    /** migrate, then write the texts of the swallowed-exception fixes in the loggers package and apply them. */
    private Map<String, String> migrateAndFillLoggerTexts(Path app, String classpath, IoAction prepare) throws IOException {
        migrate(app, classpath, prepare);
        long filled = editDecisions(app.resolve(".logast/decisions/messages.yaml"), root -> fillMessages(root, (hint, where) ->
                where.startsWith("src/main/java/com/example/e2e/loggers/") ? "Step failed" : null, false));
        assertThat(filled).as("messages.yaml has texts for the loggers package").isPositive();
        String third = run(app, "apply", app.toString(), "--auto", "--write", "--classpath-file", classpath);
        assertThat(third).doesNotContain("CHECK FAILED");
        Files.writeString(app.resolve("OUTPUT.txt"), Files.readString(app.resolve("OUTPUT.txt"))
                + "\n=== after writing the texts of the loggers package\n" + third);
        compile(app);
        return files(app);
    }

    /**
     * A house-format suffix written without the space after the comma ({@code [pledgeId={},channel={}]}) is house format:
     * a key logast puts in MDC at the entrypoint is dropped from it, and the rest is written with ", " (user report).
     */
    @Test
    void compactSuffixDropsTheKeyMdcCarries() throws IOException {
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, app -> {
            Path source = app.resolve("src/main/java/com/example/e2e/ticket/TicketController.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, """
                    package com.example.e2e.ticket;

                    import org.slf4j.Logger;
                    import org.slf4j.LoggerFactory;
                    import org.springframework.web.bind.annotation.PathVariable;
                    import org.springframework.web.bind.annotation.PostMapping;
                    import org.springframework.web.bind.annotation.RequestParam;
                    import org.springframework.web.bind.annotation.RestController;

                    @RestController
                    class TicketController {
                        private static final Logger log = LoggerFactory.getLogger(TicketController.class);

                        @PostMapping("/tickets/{pledgeId}/close")
                        void close(@PathVariable String pledgeId, @RequestParam String channel) {
                            log.info("Pledge closed [pledgeId={},channel={}]", pledgeId, channel);
                        }
                    }
                    """);
        });
        String source = actual.get("src/main/java/com/example/e2e/ticket/TicketController.java");
        assertThat(source).as("pledgeId is in MDC: it leaves the message, the rest is written with \", \"")
                .contains("log.info(\"Pledge closed [channel={}]\", channel)")
                .doesNotContain("pledgeId={}");
        golden(EXPECTED.resolveSibling("expected-compact-suffix"), actual);
    }

    /** apply twice (what one run leaves behind shows up), record the output, compile: every file of the result. */
    private Map<String, String> migrate(Path app, String classpath, IoAction prepare) throws IOException {
        copy(BEFORE, app);
        prepare.run(app);
        String first = run(app, "apply", app.toString(), "--auto", "--write", "--classpath-file", classpath);
        // the pipeline's steps settle in one pass: a second pass inside the same run applies nothing
        String trace = Files.readString(app.resolve(".logast/reports/apply-trace.txt"));
        String afterPass1 = trace.contains("== pass 2") ? trace.substring(trace.indexOf("== pass 2")) : "";
        assertThat(afterPass1).as("pass 2 of the first apply applies nothing (.logast/reports/apply-trace.txt):\n" + trace)
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
        List<Path> classes = new ArrayList<>(List.of(PLATFORM));
        if (starterClasses != null) {
            classes.add(starterClasses);
        }
        classes.addAll(List.of(extraClasses));
        compile(app, sourceEncoding, classes.toArray(Path[]::new));
        return files(app);
    }

    interface IoAction {
        void run(Path app) throws IOException;
    }

    /**
     * The result equals the main snapshot (e2e/expected). Not while -Pe2e.update re-records: another shard may be
     * rewriting that snapshot at the same moment (#110 runs shards in parallel), so the comparison would read a half-old
     * snapshot; the run after the update compares as usual.
     */
    private static void assertSameAsMain(Map<String, String> actual) throws IOException {
        if (!UPDATE) {
            assertSameFiles(files(EXPECTED), actual);
        }
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
     * #90, Spring's own resilience switches. {@code @Retryable} / {@code @ConcurrencyLimit} (Spring Framework 7) do
     * nothing without {@code @EnableResilientMethods}: logast adds it to the @SpringBootApplication class. With
     * {@code @EnableAsync} and {@code @EnableScheduling}, @Async runs on Boot's executor (unbounded queue) and the
     * @Scheduled jobs of the fixture share Boot's one-thread scheduler: both reported. An @Async naming its own executor
     * is not on Boot's. #41: an @Entity gets spring.jpa.properties.jakarta.persistence.query.timeout; a JdbcTemplate
     * created with {@code new} stays reported (spring.jdbc.template.* does not reach it), one that only wraps an
     * existing template does not. Every file must equal e2e/expected-spring-resilience.
     */
    @Test
    void springResilienceSwitchesAreTurnedOnOrReported() throws IOException {
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, app -> {
            Path dir = Files.createDirectories(app.resolve("src/main/java/com/example/e2e/carrier"));
            Files.writeString(dir.resolve("CarrierClient.java"), """
                    package com.example.e2e.carrier;

                    import org.springframework.resilience.annotation.ConcurrencyLimit;
                    import org.springframework.resilience.annotation.Retryable;
                    import org.springframework.stereotype.Component;

                    @Component
                    public class CarrierClient {
                        @Retryable(maxRetries = 2)
                        public String track(String parcelId) {
                            return "in transit";
                        }

                        @ConcurrencyLimit(4)
                        public String quote(String parcelId) {
                            return "4.20";
                        }
                    }
                    """);
            Files.writeString(dir.resolve("CarrierLabels.java"), """
                    package com.example.e2e.carrier;

                    import org.springframework.scheduling.annotation.Async;
                    import org.springframework.stereotype.Component;

                    @Component
                    public class CarrierLabels {
                        @Async
                        public void print(String parcelId) {
                            System.out.println(parcelId);
                        }

                        @Async("labelExecutor")
                        public void reprint(String parcelId) {
                            System.out.println(parcelId);
                        }
                    }
                    """);
            Files.writeString(dir.resolve("CarrierConfig.java"), """
                    package com.example.e2e.carrier;

                    import org.springframework.context.annotation.Configuration;
                    import org.springframework.scheduling.annotation.EnableAsync;
                    import org.springframework.scheduling.annotation.EnableScheduling;

                    @Configuration
                    @EnableAsync
                    @EnableScheduling
                    public class CarrierConfig {
                    }
                    """);
            // #41: JPA gets its query timeout as a JPA property; a JdbcTemplate made by hand is out of the property's reach
            Files.writeString(dir.resolve("Consignment.java"), """
                    package com.example.e2e.carrier;

                    import jakarta.persistence.Entity;

                    @Entity
                    public class Consignment {
                        private String consignmentId;
                    }
                    """);
            Files.writeString(dir.resolve("CarrierRates.java"), """
                    package com.example.e2e.carrier;

                    import javax.sql.DataSource;
                    import org.springframework.jdbc.core.JdbcTemplate;
                    import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
                    import org.springframework.stereotype.Repository;

                    @Repository
                    public class CarrierRates {
                        private final JdbcTemplate jdbc;
                        private final NamedParameterJdbcTemplate named;

                        public CarrierRates(DataSource dataSource) {
                            this.jdbc = new JdbcTemplate(dataSource);
                            this.named = new NamedParameterJdbcTemplate(jdbc);
                        }

                        public Integer rate(String zone) {
                            return jdbc.queryForObject("SELECT rate FROM carrier_rate WHERE zone = ?", Integer.class, zone);
                        }
                    }
                    """);
        });
        assertThat(actual.get("src/main/resources/application.yml")).contains("jakarta.persistence.query.timeout: 300000");
        assertThat(actual.get("FINDINGS.txt")).contains("new JdbcTemplate(..) creates a JdbcTemplate of its own")
                .doesNotContain("new NamedParameterJdbcTemplate(..)");
        assertThat(actual.get("src/main/java/com/example/e2e/E2eApplication.java"))
                .contains("@EnableResilientMethods\n@EnableConfigurationProperties(RetrySettings.class)\n@SpringBootApplication");
        assertThat(actual.get("FINDINGS.txt")).contains("SCHEDULER_SINGLE_THREAD")
                .contains("Spring Boot's auto-configured executor").contains("CarrierLabels.print)")
                .doesNotContain("RETRY_INACTIVE");
        golden(EXPECTED.resolveSibling("expected-spring-resilience"), actual);
    }

    /** #90 items 2, 6, 7: the shapes of {@link #rolloutReadinessClientsAndCachesAreReported()}. */
    private static final IoAction ROLLOUT_AND_CACHES = app -> {
        Path dir = Files.createDirectories(app.resolve("src/main/java/com/example/e2e/depot"));
        // WebClient.create() gets no Spring Boot configuration; one with its own connector is configured
        Files.writeString(dir.resolve("DepotClient.java"), """
                package com.example.e2e.depot;

                import org.springframework.http.client.reactive.ClientHttpConnector;
                import org.springframework.stereotype.Component;
                import org.springframework.web.reactive.function.client.WebClient;

                @Component
                public class DepotClient {
                    private final WebClient depots;

                    public DepotClient(ClientHttpConnector connector) {
                        this.depots = WebClient.builder().clientConnector(connector).baseUrl("http://depots").build();
                    }

                    public String slot(String depotId) {
                        WebClient slots = WebClient.create("http://slots");
                        return slots.get().uri("/slots/{id}", depotId).retrieve().bodyToMono(String.class).block();
                    }

                    public String depot(String depotId) {
                        return depots.get().uri("/depots/{id}", depotId).retrieve().bodyToMono(String.class).block();
                    }
                }
                """);
        // Apache HttpClient: createDefault() has no response timeout; the one with a RequestConfig is configured
        Files.writeString(dir.resolve("DepotFeeds.java"), """
                package com.example.e2e.depot;

                import java.util.concurrent.TimeUnit;
                import org.apache.hc.client5.http.config.RequestConfig;
                import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
                import org.apache.hc.client5.http.impl.classic.HttpClients;
                import org.springframework.stereotype.Component;

                @Component
                public class DepotFeeds {
                    private final CloseableHttpClient feeds = HttpClients.createDefault();
                    private final CloseableHttpClient alerts = HttpClients.custom()
                            .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(5, TimeUnit.SECONDS).build())
                            .build();

                    public boolean ready() {
                        return feeds != null && alerts != null;
                    }
                }
                """);
        // no cache library on the classpath: Spring Boot's simple provider never evicts
        Files.writeString(dir.resolve("DepotCacheConfig.java"), """
                package com.example.e2e.depot;

                import org.springframework.cache.annotation.EnableCaching;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                @EnableCaching
                public class DepotCacheConfig {
                }
                """);
        // graceful shutdown switched off, a shutdown phase shorter than a transaction, the database in liveness
        Path yml = app.resolve("src/main/resources/application.yml");
        String text = Files.readString(yml);
        String rollout = text.replaceFirst("(?m)^spring:\n", "spring:\n  lifecycle:\n    timeout-per-shutdown-phase: 20s\n"
                + "  transaction:\n    default-timeout: 60s\n")
                + "server:\n  shutdown: immediate\n"
                + "management:\n  endpoint:\n    health:\n      group:\n        liveness:\n          include: livenessState,db\n";
        assertThat(rollout).as("the spring: block takes the lifecycle settings").contains("timeout-per-shutdown-phase");
        Files.writeString(yml, rollout);
    };

    /**
     * #90 items 2, 6 and 7 (#29, #30): what a rollout, a slow dependency or a growing cache does to a service.
     * {@code server.shutdown: immediate} and a shutdown phase (20s) shorter than the transaction timeout (60s) are
     * reported (SHUTDOWN_NOT_GRACEFUL); the database in the liveness group (HEALTH_PROBE_MISCONFIGURED) and missing from
     * readiness (READINESS_WITHOUT_DATABASE, a decision); {@code WebClient.create()} and Apache's
     * {@code HttpClients.createDefault()} have no timeout Spring Boot could set (HTTP_CLIENT_NO_TIMEOUT), the ones
     * with a connector / RequestConfig do; {@code @EnableCaching} without a cache library keeps ConcurrentHashMaps that
     * never evict (CACHE_UNBOUNDED). Every file must equal e2e/expected-resilience-rollout; the resilience pack alone
     * reports the same.
     */
    @Test
    void rolloutReadinessClientsAndCachesAreReported() throws IOException {
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, ROLLOUT_AND_CACHES);
        String findings = actual.get("FINDINGS.txt");
        List<String> expected = List.of("server.shutdown: immediate turns graceful shutdown off",
                "(spring.lifecycle.timeout-per-shutdown-phase), less than the service lets work run "
                        + "(spring.transaction.default-timeout = ",
                "The liveness group includes db", "The readiness group does not include the database",
                "HTTP client built from WebClient.create() without timeouts",
                "Apache HttpClient built with HttpClients.createDefault() without timeouts",
                "Caches without a size bound (no cache library is on the classpath");
        assertThat(findings).contains(expected).doesNotContain("HttpClients.custom()").doesNotContain("WebClient.builder()");
        golden(EXPECTED.resolveSibling("expected-resilience-rollout"), actual);

        // the resilience pack on its own reports the same
        Map<String, String> alone = migrate(tmp.resolve("resilience-only"), CLASSPATH, app -> {
            ROLLOUT_AND_CACHES.run(app);
            Path yml = app.resolve("logast.yml");
            Files.writeString(yml, Files.readString(yml).replaceFirst("(?m)^packs: .*$", "packs: [resilience]"));
        });
        assertThat(alone.get("FINDINGS.txt")).contains(expected);
    }

    /** #105: the shapes of {@link #idempotentCallsWithoutRetryAndNestedRetriesAreReported()}. */
    private static final IoAction RATE_RETRIES = app -> {
        Path dir = Files.createDirectories(app.resolve("src/main/java/com/example/e2e/rate"));
        Files.writeString(dir.resolve("RateConfig.java"), """
                package com.example.e2e.rate;

                import org.springframework.context.annotation.Configuration;
                import org.springframework.resilience.annotation.EnableResilientMethods;

                @Configuration
                @EnableResilientMethods
                public class RateConfig {
                }
                """);
        // current: a GET a scheduled job reaches; history: a GET a request reaches; margin: a GET with @Retryable;
        // submit: a POST (a retry could book twice)
        Files.writeString(dir.resolve("RateClient.java"), """
                package com.example.e2e.rate;

                import org.springframework.resilience.annotation.Retryable;
                import org.springframework.stereotype.Component;
                import org.springframework.web.client.RestClient;

                @Component
                public class RateClient {
                    private final RestClient rest;

                    public RateClient(RestClient.Builder builder) {
                        this.rest = builder.baseUrl("http://rates").build();
                    }

                    public String current(String code) {
                        return rest.get().uri("/rates/{code}", code).retrieve().body(String.class);
                    }

                    public String history(String code) {
                        return rest.get().uri("/rates/{code}/history", code).retrieve().body(String.class);
                    }

                    @Retryable(maxRetries = 2)
                    public String margin(String code) {
                        return rest.get().uri("/margins/{code}", code).retrieve().body(String.class);
                    }

                    public void submit(String code) {
                        rest.post().uri("/quotes/{code}", code).retrieve().toBodilessEntity();
                    }
                }
                """);
        // a WebClient call with its own retry
        Files.writeString(dir.resolve("RateFeed.java"), """
                package com.example.e2e.rate;

                import java.time.Duration;
                import org.springframework.stereotype.Component;
                import org.springframework.web.reactive.function.client.WebClient;
                import reactor.util.retry.Retry;

                @Component
                public class RateFeed {
                    private final WebClient client;

                    public RateFeed(WebClient.Builder builder) {
                        this.client = builder.baseUrl("http://feed").build();
                    }

                    public String latest(String code) {
                        return client.get().uri("/latest/{code}", code).retrieve().bodyToMono(String.class)
                                .retryWhen(Retry.backoff(2, Duration.ofMillis(200))).block();
                    }
                }
                """);
        // a retried method calling another retried method: the attempts multiply
        Files.writeString(dir.resolve("RateLedger.java"), """
                package com.example.e2e.rate;

                import org.springframework.resilience.annotation.Retryable;
                import org.springframework.stereotype.Service;

                @Service
                public class RateLedger {
                    private final RateClient client;

                    public RateLedger(RateClient client) {
                        this.client = client;
                    }

                    @Retryable(maxRetries = 2)
                    public String record(String code) {
                        return client.margin(code);
                    }
                }
                """);
        Files.writeString(dir.resolve("RateSync.java"), """
                package com.example.e2e.rate;

                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;

                @Component
                public class RateSync {
                    private final RateClient client;
                    private final RateFeed feed;

                    public RateSync(RateClient client, RateFeed feed) {
                        this.client = client;
                        this.feed = feed;
                    }

                    @Scheduled(fixedDelay = 60000)
                    public void refresh() {
                        System.out.println(client.current("EUR") + feed.latest("EUR"));
                    }
                }
                """);
        Files.writeString(dir.resolve("RateController.java"), """
                package com.example.e2e.rate;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class RateController {
                    private final RateClient client;
                    private final RateLedger ledger;

                    public RateController(RateClient client, RateLedger ledger) {
                        this.client = client;
                        this.ledger = ledger;
                    }

                    @GetMapping("/rates/{code}/history")
                    public String history(@PathVariable String code) {
                        return client.history(code);
                    }

                    @GetMapping("/rates/{code}/margin")
                    public String margin(@PathVariable String code) {
                        return ledger.record(code);
                    }

                    @PostMapping("/rates/{code}/quotes")
                    public void quote(@PathVariable String code) {
                        client.submit(code);
                    }

                    @GetMapping("/rates/{code}/archive")
                    public String archive(@PathVariable String code) {
                        return archive.archived(code) + spread.spread(code);
                    }
                }
                """.replace("    private final RateLedger ledger;\n", "    private final RateLedger ledger;\n    private final RateArchive archive;\n"
                + "    private final RateSpread spread;\n").replace("RateController(RateClient client, RateLedger ledger) {\n",
                "RateController(RateClient client, RateLedger ledger, RateArchive archive, RateSpread spread) {\n"
                + "        this.archive = archive;\n        this.spread = spread;\n"));
        // #109: a retry the proxy never sees (a private helper called on itself), and a call made through such a helper
        Files.writeString(dir.resolve("RateArchive.java"), """
                package com.example.e2e.rate;

                import org.springframework.resilience.annotation.Retryable;
                import org.springframework.stereotype.Component;
                import org.springframework.web.client.RestClient;

                @Component
                public class RateArchive {
                    private final RestClient rest = RestClient.create("http://rates");

                    public String archived(String code) {
                        return fetch(code);
                    }

                    @Retryable(maxRetries = 2)
                    private String fetch(String code) {
                        return rest.get().uri("/rates/{code}/archive", code).retrieve().body(String.class);
                    }
                }
                """);
        Files.writeString(dir.resolve("RateSpread.java"), """
                package com.example.e2e.rate;

                import org.springframework.stereotype.Component;
                import org.springframework.web.client.RestClient;

                @Component
                public class RateSpread {
                    private final RestClient rest = RestClient.create("http://rates");

                    public String spread(String code) {
                        return fetch(code);
                    }

                    private String fetch(String code) {
                        return rest.get().uri("/rates/{code}/spread", code).retrieve().body(String.class);
                    }
                }
                """);
    };

    /**
     * #105: retry where it is safe and missing, and not twice. A GET nothing retries is reported (RETRY_RECOMMENDED):
     * reached from a scheduled job with backoff settings from resilience.retry.defaults, from a request with at most one
     * quick retry inside the caller's timeout. A GET with @Retryable, a WebClient call with retryWhen and a POST (a
     * retry could repeat its side effect) are not. A @Retryable method calling another @Retryable method through its
     * bean multiplies the attempts (NESTED_RETRY). Every file must equal e2e/expected-retry-coverage; the resilience
     * pack alone reports the same.
     */
    @Test
    void idempotentCallsWithoutRetryAndNestedRetriesAreReported() throws IOException {
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, RATE_RETRIES);
        String findings = actual.get("FINDINGS.txt");
        List<String> expected = List.of(
                "RateClient.current makes an idempotent HTTP call that nothing retries, reached from",
                "(scheduled job): one dropped connection or 503 fails the run",
                "RateClient.history makes an idempotent HTTP call that nothing retries, reached from",
                "(request): one dropped connection or 503 fails the request",
                "Retry it at most once, with a short backoff, inside the caller's timeout",
                "Retry it with backoff: @Retryable(maxRetriesString = \"${resilience.retry.defaults.max-retries}\"",
                "RateLedger.record retries and calls RateClient.margin, which retries again",
                // #109: self-invocation: the retry goes where the proxy sees the call; a retry it never sees is reported
                "RateSpread.spread calls RateSpread.fetch, which makes an idempotent HTTP call that nothing retries",
                "The retry on RateArchive.fetch never runs: it is private and only called from its own class");
        assertThat(findings).contains(expected).doesNotContain("RateClient.margin makes").doesNotContain("RateClient.submit makes")
                .doesNotContain("RateFeed.latest makes").doesNotContain("RateClient.margin retries and calls");
        golden(EXPECTED.resolveSibling("expected-retry-coverage"), actual);

        // #109: retry.yaml lists the recommended calls next to the usages; answered there, the next apply acts on them
        Path rated = tmp.resolve("app");
        Path retry = rated.resolve(".logast/decisions/retry.yaml");
        String listed = Files.readString(retry);
        assertThat(listed).as("candidates next to the usages, the nested retry noted at its usage")
                .contains("candidates:\n  RateClient.current:").contains("  RateClient.history:")
                .contains("    # retries inside RateClient.margin, which retries too");
        Files.writeString(retry, listed
                .replaceFirst("(RateClient\\.current:[^\n]*\n    retry: )\"\"", "$1no")
                // yes with its settings decided at once (no second round): its own block, starting at 2 retries
                .replaceFirst("(RateClient\\.history:[^\n]*\n    retry: )\"\"([^\n]*\n)",
                        "$1yes$2    specific: true\n    values:\n      max-retries: 2\n"));
        String third = run(rated, "apply", rated.toString(), "--auto", "--write", "--classpath-file", CLASSPATH);
        String trace = Files.readString(rated.resolve(".logast/reports/apply-trace.txt"));
        assertThat(trace.contains("== pass 2") ? trace.substring(trace.indexOf("== pass 2")) : "")
                .as("answered in one pass:\n" + trace).doesNotContain("applying");
        assertThat(run(rated, "apply", rated.toString(), "--auto", "--write", "--classpath-file", CLASSPATH))
                .as("a second apply changes nothing").contains("applied (0):").contains("files (0):");
        assertThat(third).doesNotContain("CHECK FAILED");
        Files.writeString(rated.resolve("FINDINGS.txt"), run(rated, "scan", rated.toString(), "--classpath-file", CLASSPATH));
        compile(rated);
        Map<String, String> decided = files(rated);
        assertThat(decided.get("src/main/java/com/example/e2e/rate/RateClient.java")).as("yes: retried, its values in application.yml")
                .contains("@Retryable(");
        assertThat(decided.get("FINDINGS.txt")).as("both answered: neither is recommended again")
                .doesNotContain("RateClient.current makes").doesNotContain("RateClient.history makes");
        assertThat(decided.get(".logast/decisions/retry.yaml")).as("no stays listed; yes is a usage now, with the candidate's settings")
                .contains("  RateClient.current:\n    retry: \"no\"")
                .contains("\n  RateClient.history:    # Spring Framework @Retryable, src/main/java/com/example/e2e/rate/RateClient.java:20\n"
                        + "    specific: true\n    values:\n      max-retries: 2\n");
        assertThat(decided.get("src/main/resources/application.yml")).as("the candidate's values, in the same apply")
                .contains("      rate-client:\n        history:\n          max-retries: 2\n");
        golden(EXPECTED.resolveSibling("expected-retry-decided"), decided);

        // the resilience pack on its own reports the same
        Map<String, String> alone = migrate(tmp.resolve("resilience-only"), CLASSPATH, app -> {
            RATE_RETRIES.run(app);
            Path yml = app.resolve("logast.yml");
            Files.writeString(yml, Files.readString(yml).replaceFirst("(?m)^packs: .*$", "packs: [resilience]"));
        });
        assertThat(alone.get("FINDINGS.txt")).contains(expected);
    }

    /** #104: stand-ins for Spring Retry 2.x (not on the e2e classpath): the attributes the retry settings scenario uses. */
    private static final Map<String, String> SPRING_RETRY_STUBS = Map.of(
            "org/springframework/retry/annotation/Retryable.java", """
                    package org.springframework.retry.annotation;

                    import java.lang.annotation.ElementType;
                    import java.lang.annotation.Retention;
                    import java.lang.annotation.RetentionPolicy;
                    import java.lang.annotation.Target;

                    @Target({ElementType.METHOD, ElementType.TYPE})
                    @Retention(RetentionPolicy.RUNTIME)
                    public @interface Retryable {
                        Class<? extends Throwable>[] value() default {};

                        Class<? extends Throwable>[] retryFor() default {};

                        int maxAttempts() default 3;

                        String maxAttemptsExpression() default "";

                        Backoff backoff() default @Backoff();
                    }
                    """,
            "org/springframework/retry/annotation/Backoff.java", """
                    package org.springframework.retry.annotation;

                    import java.lang.annotation.Retention;
                    import java.lang.annotation.RetentionPolicy;

                    @Retention(RetentionPolicy.RUNTIME)
                    public @interface Backoff {
                        long value() default 1000;

                        long delay() default 0;

                        long maxDelay() default 0;

                        double multiplier() default 0;

                        String delayExpression() default "";

                        String maxDelayExpression() default "";

                        String multiplierExpression() default "";

                        boolean random() default false;
                    }
                    """,
            "org/springframework/retry/annotation/EnableRetry.java", """
                    package org.springframework.retry.annotation;

                    import java.lang.annotation.ElementType;
                    import java.lang.annotation.Retention;
                    import java.lang.annotation.RetentionPolicy;
                    import java.lang.annotation.Target;

                    @Target(ElementType.TYPE)
                    @Retention(RetentionPolicy.RUNTIME)
                    public @interface EnableRetry {
                    }
                    """);

    /** #104: the shapes of {@link #retrySettingsMoveIntoConfiguration()}. */
    private static final IoAction PARCEL_RETRIES = app -> {
        Path dir = Files.createDirectories(app.resolve("src/main/java/com/example/e2e/parcel"));
        Files.writeString(dir.resolve("ParcelConfig.java"), """
                package com.example.e2e.parcel;

                import org.springframework.context.annotation.Configuration;
                import org.springframework.resilience.annotation.EnableResilientMethods;
                import org.springframework.retry.annotation.EnableRetry;

                @Configuration
                @EnableResilientMethods
                @EnableRetry
                public class ParcelConfig {
                }
                """);
        // Spring Framework 7: two usages with the same settings share them, one with settings of its own (in seconds)
        // keeps them, one that already reads the team's own property is left alone
        Files.writeString(dir.resolve("ParcelClient.java"), """
                package com.example.e2e.parcel;

                import java.io.IOException;
                import java.util.concurrent.TimeUnit;
                import org.springframework.resilience.annotation.Retryable;
                import org.springframework.stereotype.Component;

                @Component
                public class ParcelClient {
                    @Retryable(maxRetries = 2, delay = 500)
                    public String track(String parcelId) {
                        return "in transit";
                    }

                    @Retryable(maxRetries = 2, delay = 500)
                    public String quote(String parcelId) {
                        return "4.20";
                    }

                    @Retryable(value = IOException.class, maxRetries = 5, delay = 2, multiplier = 2.0, timeUnit = TimeUnit.SECONDS)
                    public String label(String parcelId) throws IOException {
                        return "label";
                    }

                    @Retryable(maxRetriesString = "${parcel.audit.retries:1}")
                    public String audit(String parcelId) {
                        return "audited";
                    }
                }
                """);
        // Spring Retry: a backoff with a multiplier, and a bare @Retryable on the library's defaults
        Files.writeString(dir.resolve("ParcelGateway.java"), """
                package com.example.e2e.parcel;

                import org.springframework.retry.annotation.Backoff;
                import org.springframework.retry.annotation.Retryable;
                import org.springframework.stereotype.Component;

                @Component
                public class ParcelGateway {
                    @Retryable(retryFor = IllegalStateException.class, maxAttempts = 4, backoff = @Backoff(delay = 200, multiplier = 1.5))
                    public void send(String parcelId) {
                        System.out.println(parcelId);
                    }

                    @Retryable
                    public void cancel(String parcelId) {
                        System.out.println(parcelId);
                    }
                }
                """);
    };

    /**
     * #104: retry settings come from configuration. Literal attempts and delays of Spring Framework 7's and Spring
     * Retry's @Retryable move into application.yml: one shared block per kind (resilience.retry.defaults,
     * resilience.retry.spring-retry-defaults) with the most common settings, a block of its own
     * (resilience.retry.usages.<class>.<method>) for a usage whose settings differ, so nothing changes behaviour. The
     * annotations read them through placeholders (Framework: maxRetriesString = "${…}"; Spring Retry:
     * maxAttemptsExpression = "#{${…}}"), the generated RetrySettings record binds them, @EnableConfigurationProperties
     * registers it. A usage reading the team's own property is left alone. .logast/decisions/retry.yaml lists the usages: marking
     * one specific gives it its own block on the next apply, and the run after that changes nothing. Every file must
     * equal e2e/expected-retry-settings; the resilience pack alone gives the same code and configuration.
     */
    @Test
    void retrySettingsMoveIntoConfiguration() throws IOException {
        String classpath = libraryClasspath("spring-retry", SPRING_RETRY_STUBS).toString();
        Map<String, String> actual = migrate(tmp.resolve("app"), classpath, PARCEL_RETRIES);
        String client = actual.get("src/main/java/com/example/e2e/parcel/ParcelClient.java");
        String gateway = actual.get("src/main/java/com/example/e2e/parcel/ParcelGateway.java");
        String yml = actual.get("src/main/resources/application.yml");
        assertThat(client).contains("@Retryable(maxRetriesString = \"${resilience.retry.defaults.max-retries}\", "
                        + "delayString = \"${resilience.retry.defaults.delay}\")")
                .contains("@Retryable(value = IOException.class, timeUnit = TimeUnit.SECONDS, "
                        + "maxRetriesString = \"${resilience.retry.usages.parcel-client.label.max-retries}\"")
                .as("the team's own property").contains("@Retryable(maxRetriesString = \"${parcel.audit.retries:1}\")");
        assertThat(gateway).contains("maxAttemptsExpression = \"#{${resilience.retry.spring-retry-defaults.max-attempts}}\"")
                .contains("backoff = @Backoff(delayExpression = \"#{${resilience.retry.usages.parcel-gateway.cancel.delay}}\")")
                .contains("import org.springframework.retry.annotation.Backoff;");
        assertThat(yml).contains("""
                resilience:
                  retry:
                    defaults:
                      max-retries: 2
                      delay: 500ms
                """).contains("""
                        label:
                          max-retries: 5
                          delay: 2000ms
                          multiplier: 2.0
                """).contains("""
                    spring-retry-defaults:
                      max-attempts: 4
                      delay: 200
                      multiplier: 1.5
                """);
        assertThat(actual.get("src/main/java/com/example/e2e/RetrySettings.java"))
                .contains("@ConfigurationProperties(\"resilience.retry\")");
        assertThat(actual.get(MAIN_CLASS)).contains("@EnableConfigurationProperties(RetrySettings.class)\n@SpringBootApplication");
        assertThat(actual.get(".logast/decisions/retry.yaml")).contains("  ParcelClient.track:").contains("  ParcelClient.label:")
                .doesNotContain("ParcelClient.audit");
        assertThat(actual.get("OUTPUT.txt")).contains(".logast/decisions/retry.yaml (optional)");
        golden(EXPECTED.resolveSibling("expected-retry-settings"), actual);

        // marked specific: the next apply gives ParcelClient.track a block of its own, starting from the shared values
        Path app = tmp.resolve("app");
        Path work = app.resolve(".logast/decisions/retry.yaml");
        String marked = Files.readString(work).replaceFirst("(  ParcelClient\\.track:.*\\n    specific: )false", "$1true");
        assertThat(marked).isNotEqualTo(Files.readString(work));
        Files.writeString(work, marked);
        String apply = run(app, "apply", app.toString(), "--auto", "--write", "--classpath-file", classpath);
        assertThat(apply).doesNotContain("CHECK FAILED");
        String moved = Files.readString(app.resolve("src/main/java/com/example/e2e/parcel/ParcelClient.java"));
        assertThat(moved).contains("@Retryable(maxRetriesString = \"${resilience.retry.usages.parcel-client.track.max-retries}\", "
                        + "delayString = \"${resilience.retry.usages.parcel-client.track.delay}\")\n    public String track")
                .as("the other usage stays on the shared settings")
                .contains("@Retryable(maxRetriesString = \"${resilience.retry.defaults.max-retries}\", "
                        + "delayString = \"${resilience.retry.defaults.delay}\")\n    public String quote");
        assertThat(Files.readString(app.resolve("src/main/resources/application.yml")))
                .contains("          multiplier: 2.0\n        track:\n          max-retries: 2\n          delay: 500ms\n");
        assertThat(Files.readString(work)).contains("  ParcelClient.track:    # Spring Framework @Retryable, "
                + "src/main/java/com/example/e2e/parcel/ParcelClient.java:11\n    specific: true\n");
        String again = run(app, "apply", app.toString(), "--auto", "--write", "--classpath-file", classpath);
        assertThat(again).as("the run after that changes nothing").contains("applied (0):").contains("files (0):")
                .doesNotContain("CHECK FAILED");
        compile(app, "UTF-8", PLATFORM, extraClasses[0]);

        // the resilience pack on its own writes the same code and configuration
        Map<String, String> alone = migrate(tmp.resolve("resilience-only"), classpath, app2 -> {
            PARCEL_RETRIES.run(app2);
            Path policy = app2.resolve("logast.yml");
            Files.writeString(policy, Files.readString(policy).replaceFirst("(?m)^packs: .*$", "packs: [resilience]"));
        });
        for (String f : List.of("src/main/java/com/example/e2e/parcel/ParcelClient.java", "src/main/java/com/example/e2e/parcel/ParcelGateway.java",
                "src/main/java/com/example/e2e/RetrySettings.java", ".logast/decisions/retry.yaml")) {
            assertThat(alone.get(f)).as(f).isEqualTo(actual.get(f));
        }
        assertThat(alone.get("src/main/resources/application.yml")).contains("    spring-retry-defaults:\n      max-attempts: 4\n");
    }

    /** #90 item 5, the retry follow-up and the WebClient.Builder: the shapes of {@link #poisonMessagesNestedRetriesAndWebClientTimeouts()}. */
    private static final IoAction STOCK = app -> {
        Path dir = Files.createDirectories(app.resolve("src/main/java/com/example/e2e/stock"));
        Files.writeString(dir.resolve("StockConfig.java"), """
                package com.example.e2e.stock;

                import org.springframework.context.annotation.Configuration;
                import org.springframework.resilience.annotation.EnableResilientMethods;

                @Configuration
                @EnableResilientMethods
                public class StockConfig {
                }
                """);
        // reserve joins book's transaction: its retries run in a transaction already marked rollback-only; release has
        // a transaction of its own per attempt; restock is not called inside a transaction
        Files.writeString(dir.resolve("StockWriter.java"), """
                package com.example.e2e.stock;

                import org.springframework.resilience.annotation.Retryable;
                import org.springframework.stereotype.Component;
                import org.springframework.transaction.annotation.Propagation;
                import org.springframework.transaction.annotation.Transactional;

                @Component
                public class StockWriter {
                    @Retryable(maxRetries = 2)
                    @Transactional
                    public int reserve(String itemId, int quantity) {
                        return quantity;
                    }

                    @Retryable(maxRetries = 2)
                    @Transactional(propagation = Propagation.REQUIRES_NEW)
                    public int release(String itemId) {
                        return 0;
                    }

                    @Retryable(maxRetries = 2)
                    @Transactional
                    public int restock(String itemId) {
                        return 1;
                    }
                }
                """);
        Files.writeString(dir.resolve("StockLedger.java"), """
                package com.example.e2e.stock;

                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;

                @Service
                public class StockLedger {
                    private final StockWriter writer;

                    public StockLedger(StockWriter writer) {
                        this.writer = writer;
                    }

                    @Transactional
                    public int book(String itemId, int quantity) {
                        return writer.reserve(itemId, quantity) + writer.release(itemId);
                    }

                    public int refill(String itemId) {
                        return writer.restock(itemId);
                    }
                }
                """);
        // onReturn is requeued forever when it throws; onAudit acknowledges itself; StockRejects rejects for good
        Files.writeString(dir.resolve("StockEvents.java"), """
                package com.example.e2e.stock;

                import org.springframework.amqp.rabbit.annotation.RabbitListener;
                import org.springframework.stereotype.Component;

                @Component
                public class StockEvents {
                    private final StockLedger ledger;

                    public StockEvents(StockLedger ledger) {
                        this.ledger = ledger;
                    }

                    @RabbitListener(queues = "stock-returns")
                    public void onReturn(String itemId) {
                        ledger.refill(itemId);
                    }

                    @RabbitListener(queues = "stock-audits", ackMode = "MANUAL")
                    public void onAudit(String itemId) {
                        ledger.refill(itemId);
                    }
                }
                """);
        Files.writeString(dir.resolve("StockRejects.java"), """
                package com.example.e2e.stock;

                import org.springframework.amqp.AmqpRejectAndDontRequeueException;
                import org.springframework.amqp.rabbit.annotation.RabbitListener;
                import org.springframework.stereotype.Component;

                @Component
                public class StockRejects {
                    @RabbitListener(queues = "stock-rejects")
                    public void onReject(String itemId) {
                        if (itemId.isBlank()) {
                            throw new AmqpRejectAndDontRequeueException("blank item id");
                        }
                    }
                }
                """);
        // a WebClient built from the builder Spring Boot injects: the timeout properties reach it
        Files.writeString(dir.resolve("StockFeed.java"), """
                package com.example.e2e.stock;

                import org.springframework.stereotype.Component;
                import org.springframework.web.reactive.function.client.WebClient;

                @Component
                public class StockFeed {
                    private final WebClient client;

                    public StockFeed(WebClient.Builder builder) {
                        this.client = builder.baseUrl("http://stock").build();
                    }

                    public String level(String itemId) {
                        return client.get().uri("/levels/{id}", itemId).retrieve().bodyToMono(String.class).block();
                    }
                }
                """);
    };

    /** The scenario's build declares Spring Boot {@code version}. */
    private static IoAction bootVersion(String version) {
        return app -> {
            Path build = app.resolve("build.gradle.kts");
            String text = Files.readString(build);
            String declared = text.replace("plugins { java }", "plugins {\n    java\n    id(\"org.springframework.boot\") version \""
                    + version + "\"\n}");
            assertThat(declared).as("the scenario declares the target Spring Boot version").isNotEqualTo(text);
            Files.writeString(build, declared);
        };
    }

    /**
     * #90 item 5 and the follow-ups of item 4 and 6. A @RabbitListener on Spring Boot's defaults requeues a message
     * whose listener throws without end (RABBIT_NO_ERROR_HANDLER); one with its own ackMode, or whose class throws
     * AmqpRejectAndDontRequeueException, is not reported. A @Retryable @Transactional method called from another
     * transaction joins it, so its retries run in a transaction already marked rollback-only (RETRY_IN_TRANSACTION);
     * REQUIRES_NEW, or no outer transaction, is fine. A WebClient built from the injected WebClient.Builder gets
     * Spring Boot's timeout properties: spring.http.clients.* on Boot 4 (with the RestClient), spring.http.reactiveclient.*
     * next to spring.http.client.* on Boot 3.5, and a report on Boot 3.4, which has no such property. Every file must
     * equal e2e/expected-resilience-messaging; the resilience pack alone reports the same.
     */
    @Test
    void poisonMessagesNestedRetriesAndWebClientTimeouts() throws IOException {
        Map<String, String> actual = migrate(tmp.resolve("app"), CLASSPATH, STOCK);
        String findings = actual.get("FINDINGS.txt");
        List<String> expected = List.of("RabbitMQ listeners on the default error handling: a message whose listener throws is "
                        + "rejected and requeued, so a message that always fails is redelivered without end "
                        + "(PledgeMessages.onReturn, StockEvents.onReturn)",
                "StockWriter.reserve is @Retryable and @Transactional, but runs inside the transaction of StockLedger.book → "
                        + "StockWriter.reserve");
        assertThat(findings).contains(expected).doesNotContain("StockWriter.release is").doesNotContain("StockWriter.restock is")
                .doesNotContain("StockEvents.onAudit)").doesNotContain("StockRejects.onReject)");
        assertThat(actual.get("src/main/resources/application.yml")).contains("  http:\n    clients:\n      connect-timeout:")
                .doesNotContain("reactiveclient");
        golden(EXPECTED.resolveSibling("expected-resilience-messaging"), actual);

        // the resilience pack on its own reports the same
        Map<String, String> alone = migrate(tmp.resolve("resilience-only"), CLASSPATH, app -> {
            STOCK.run(app);
            Path yml = app.resolve("logast.yml");
            Files.writeString(yml, Files.readString(yml).replaceFirst("(?m)^packs: .*$", "packs: [resilience]"));
        });
        assertThat(alone.get("FINDINGS.txt")).contains(expected);

        // Spring Boot 3.5: the WebClient gets its own property group, the RestClient spring.http.client.*
        Map<String, String> boot35 = migrate(tmp.resolve("boot35"), CLASSPATH, app -> {
            STOCK.run(app);
            bootVersion("3.5.7").run(app);
        });
        assertThat(boot35.get("src/main/resources/application.yml"))
                .contains("    client:\n      connect-timeout: 5s\n      read-timeout: 30s\n")
                .contains("    reactiveclient:\n      connect-timeout: 5s\n      read-timeout: 30s\n")
                .doesNotContain("clients:");

        // Spring Boot 3.4 has no property for WebClient timeouts: reported instead
        Path boot34 = tmp.resolve("boot34");
        copy(BEFORE, boot34);
        STOCK.run(boot34);
        bootVersion("3.4.10").run(boot34);
        assertThat(run(boot34, "scan", boot34.toString(), "--classpath-file", CLASSPATH))
                .contains("WebClients without connect/read timeout").contains("Spring Boot 3.4 has no property for them")
                .contains("Sets spring.http.client.connect-timeout=");
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
     * finding, and no id may change, so baselines, messages.yaml and --only keep matching.
     */
    @Test
    void orderIdsSurviveShiftedLines() throws IOException {
        Path app = tmp.resolve("app");
        copy(BEFORE, app);
        java.util.function.Supplier<List<String>> ids = () -> {
            run(app, "scan", app.toString(), "--classpath-file", CLASSPATH);
            try {
                return java.util.regex.Pattern.compile("\"id\" ?: ?\"([A-Z]+-[0-9a-f]+)\"")
                        .matcher(Files.readString(app.resolve(".logast/reports/orders.json"))).results().map(r -> r.group(1)).sorted().toList();
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
                // org.orchardlib stands in for a company library under a group of its own (#59): not a public one
                for (String word : List.of("pledge", "sponsorship", "stipend", "subsidy", "kiosk", "allot", "voucher", "orchardlib", "week")) {
                    assertThat(text).as(f + " still says " + word).doesNotContain(word);
                }
            }
        }
        compile(shape, "UTF-8", platformAsExported(app.resolve(".logast/shape-mapping.txt")));
        // findings per rule, from the json orders (the summary no longer lists rules, #101)
        java.util.function.Function<Path, List<String>> rules = root -> {
            Map<String, Integer> perRule = new TreeMap<>();
            try {
                dev.logast.orders.OrderJson.MAPPER.readTree(run(root, "scan", root.toString(), "--format", "json", "--classpath-file", CLASSPATH))
                        .forEach(o -> o.path("fixes").forEach(f -> perRule.merge(f.asText(), 1, Integer::sum)));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return perRule.entrySet().stream().filter(e -> !e.getKey().equals("SETUP_SUPPORT_STARTER"))
                    .map(e -> e.getKey() + " " + e.getValue()).toList();
        };
        assertThat(rules.apply(shape)).as("the export finds what the original finds").isEqualTo(rules.apply(app));
    }

    @Test
    void rewordingTheGeneratedLinesConverges() throws IOException {
        Path app = tmp.resolve("app");
        copy(BEFORE, app);
        run(app, "apply", app.toString(), "--auto", "--write", "--classpath-file", CLASSPATH);

        Path fills = app.resolve(".logast/decisions/messages.yaml");
        var mapper = dev.logast.apply.DecisionYaml.MAPPER;
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
        Files.writeString(fills, dev.logast.apply.DecisionYaml.text(dev.logast.apply.DecisionYaml.header(fills), root));

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
        Path job = app.resolve("src/main/java/com/example/e2e/pledge/PledgeCleanupJob.java");
        String text = Files.readString(job);
        assertThat(text).contains("\"Pledge cleanup started\"", "\"Pledge cleanup completed [pledgeCount={}]\"");
        Files.writeString(job, text.replace("\"Pledge cleanup started\"", "\"Nightly sweep begins\"")
                .replace("\"Pledge cleanup completed [pledgeCount={}]\"", "\"Nightly sweep done, closed {} pledges\""));

        run(app, "apply", app.toString(), "--auto", "--write", "--classpath-file", CLASSPATH);
        String after = Files.readString(job);
        assertThat(after).doesNotContain("Pledge cleanup started").doesNotContain("Pledge cleanup completed");
        assertThat(after.split("log\\.info\\(", -1).length - 1).as(after).isEqualTo(2);   // still one start, one end
    }

    /**
     * A service migrated by an earlier logast version has its scopes too low (calculate, calculateStipend). A new run
     * puts the scope at the top of the flow (processRow) and unwraps the old one that repeats its value (calculate gets
     * the same kioskData). #79: the scopes of one pledge of the matching list (calculateStipend, the subsidy check) are
     * not provably the row's pledge id: they stay and are reported for a decision.
     */
    @Test
    void scopesLeftTooLowByAnEarlierRunMoveUp() throws IOException {
        Path app = tmp.resolve("app");
        copy(BEFORE, app);
        Path calc = app.resolve("src/main/java/com/example/e2e/stipend/StipendCalculator.java");
        // the calculator as an earlier logast version left it: scopes in calculate and calculateStipend
        Files.writeString(calc, """
                package com.example.e2e.stipend;

                import com.example.e2e.logging.MdcScope;
                import java.math.BigDecimal;
                import java.util.List;
                import java.util.Optional;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                public final class StipendCalculator {
                    private static final Logger log = LoggerFactory.getLogger(StipendCalculator.class);

                    private StipendCalculator() {
                    }

                    public static StipendOutcome calculate(KioskTotals kioskData, List<PledgeDetails> matchingPledges) {
                        try (var mdc = MdcScope.of("pledgeId", kioskData.pledgeId())) {
                            log.debug("Calculating stipend [kioskDay={}, matchingPledges={}]", kioskData.kioskDay(), matchingPledges.size());
                            if (matchingPledges.isEmpty()) {
                                log.debug("Pledge not found in the pledge list");
                                return new StipendOutcome(true, BigDecimal.ZERO);
                            }
                            return evaluateOutcome(kioskData, matchingPledges);
                        }
                    }

                    private static StipendOutcome evaluateOutcome(KioskTotals kioskData, List<PledgeDetails> sortedPledges) {
                        for (PledgeDetails pledge : sortedPledges) {
                            Optional<String> exclusion = validatePledge(kioskData, pledge);
                            if (exclusion.isEmpty()) {
                                return calculateStipend(kioskData, pledge);
                            }
                            log.trace("Pledge excluded [pledgeId={}, reason={}]", pledge.pledgeId(), exclusion.get());
                        }
                        return new StipendOutcome(true, BigDecimal.ZERO);
                    }

                    private static Optional<String> validatePledge(KioskTotals kioskData, PledgeDetails pledge) {
                        for (PledgeCheck check : PledgeChecks.all()) {
                            Optional<String> result = check.validate(pledge, kioskData);
                            if (result.isPresent()) {
                                return result;
                            }
                        }
                        return Optional.empty();
                    }

                    private static StipendOutcome calculateStipend(KioskTotals kioskData, PledgeDetails pledge) {
                        try (var mdc = MdcScope.of("pledgeId", pledge.pledgeId())) {
                            BigDecimal result = kioskData.totalDiscount().multiply(pledge.subsidyPercentage());
                            log.debug("Stipend calculated [kioskDay={}, result={}]", kioskData.kioskDay(), result);
                            return new StipendOutcome(false, result);
                        }
                    }
                }
                """);

        // and a check with a two-key scope (pledgeId + sponsorshipId), as earlier versions wrote them
        Path checks = app.resolve("src/main/java/com/example/e2e/stipend/PledgeChecks.java");
        Files.writeString(checks, Files.readString(checks)
                .replace("import java.util.List;", "import com.example.e2e.logging.MdcScope;\nimport java.util.List;")
                .replace("""
                                    log.trace(CHECK_LOG_MESSAGE, "subsidy", pledge.pledgeId(), pledge.sponsorshipId(), kioskData.kioskDay());
                                    if (pledge.subsidyPercentage() == null) {
                                        log.debug("No subsidy found [pledgeId={}, sponsorshipId={}]", pledge.pledgeId(), pledge.sponsorshipId());
                                        return Optional.of("NO_SUBSIDY");
                                    }
                                    return Optional.empty();
                        """, """
                                    try (var mdc = MdcScope.of("pledgeId", pledge.pledgeId(), "sponsorshipId", pledge.sponsorshipId())) {
                                        log.trace(CHECK_LOG_MESSAGE, "subsidy", pledge.pledgeId(), pledge.sponsorshipId(), kioskData.kioskDay());
                                        if (pledge.subsidyPercentage() == null) {
                                            log.debug("No subsidy found");
                                            return Optional.of("NO_SUBSIDY");
                                        }
                                        return Optional.empty();
                                    }
                        """));
        assertThat(Files.readString(checks)).as("test setup").contains("MdcScope.of(\"pledgeId\", pledge.pledgeId(), \"sponsorshipId\"");

        String out = run(app, "apply", app.toString(), "--auto", "--write", "--classpath-file", CLASSPATH);
        assertThat(out).doesNotContain("CHECK FAILED");
        assertThat(Files.readString(app.resolve("src/main/java/com/example/e2e/stipend/TallyService.java")))
                .contains("MdcScope.of(\"pledgeId\", kioskData.pledgeId())");
        String calculator = Files.readString(calc);
        assertThat(calculator).as("the same expression on the parameter processRow passes: unwrapped")
                .doesNotContain("MdcScope.of(\"pledgeId\", kioskData.pledgeId())")
                .as("#79: one pledge of a list is not provably the row's pledge: kept and reported")
                .contains("MdcScope.of(\"pledgeId\", pledge.pledgeId())");
        assertThat(Files.readString(checks)).as(Files.readString(checks)).contains("MdcScope.of(\"pledgeId\", pledge.pledgeId(), \"sponsorshipId\"");
        String scan = run(app, "scan", app.toString(), "--classpath-file", CLASSPATH);
        assertThat(scan).contains("MDC_NESTED_KEY  src/main/java/com/example/e2e/stipend/StipendCalculator.java")
                .contains("MDC_NESTED_KEY  src/main/java/com/example/e2e/stipend/PledgeChecks.java");
        compile(app);
    }

    /** Runs the CLI in-process; stdout with the temp path, timings and colours normalised. */
    /** What the commands run since it was last cleared printed to stderr (progress, warnings: not part of OUTPUT.txt). */
    private static final StringBuilder STDERR = new StringBuilder();

    private static String run(Path app, String... args) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(errBuf, true, StandardCharsets.UTF_8));
            new CommandLine(new Main()).execute(args);
        } finally {
            System.setOut(out);
            System.setErr(err);
            STDERR.append(errBuf.toString(StandardCharsets.UTF_8));
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
                out.put(rel, text(Files.readAllBytes(p)));
            }
        }
        return out;
    }

    /** UTF-8 text, or null when the bytes are not UTF-8. */
    private static String utf8(byte[] b) {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return null;
        }
    }

    /** UTF-8 text, or ISO-8859-1 for a file that is not UTF-8 (a legacy source): what it says, byte for byte. */
    private static String text(byte[] b) {
        String s = utf8(b);
        return s != null ? s : new String(b, StandardCharsets.ISO_8859_1);
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
        compile(root, "UTF-8");
    }

    private static void compile(Path root, String encoding) throws IOException {
        compile(root, encoding, PLATFORM);
    }

    /**
     * e2e/platform as the receiver of a shape export rebuilds it: the company library (outside the public groups) with
     * its words replaced as the export's mapping says, the public stand-ins as they are.
     */
    private static Path platformAsExported(Path mappingFile) throws IOException {
        Map<String, String> alias = new HashMap<>();
        for (String line : Files.readAllLines(mappingFile)) {
            int eq = line.indexOf(" = ");
            if (!line.startsWith("#") && eq > 0) {
                alias.put(line.substring(eq + 3), line.substring(0, eq));
            }
        }
        java.util.regex.Pattern word = java.util.regex.Pattern.compile("[A-Z]+(?![a-z])|[A-Z]?[a-z]+|[0-9]+");
        java.util.function.UnaryOperator<String> mapId = id -> javax.lang.model.SourceVersion.isKeyword(id) || id.equals("record")
                ? id : word.matcher(id).replaceAll(m -> {
            String w = m.group();
            String a = alias.get(w.toLowerCase(Locale.ROOT));
            return a == null ? w : w.length() > 1 && w.equals(w.toUpperCase(Locale.ROOT)) ? a.toUpperCase(Locale.ROOT)
                    : Character.isUpperCase(w.charAt(0)) ? Character.toUpperCase(a.charAt(0)) + a.substring(1) : a;
        });
        Path out = Files.createTempDirectory("logast-e2e-platform-shape");
        List<String> args = new ArrayList<>(List.of("-d", out.resolve("classes").toString()));
        try (Stream<Path> s = Files.walk(BEFORE.resolveSibling("platform"))) {
            for (Path f : s.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(f).replaceAll("(?s)/\\*.*?\\*/", "");
                String name = f.getFileName().toString().replace(".java", "");
                java.util.regex.Matcher pkg = java.util.regex.Pattern.compile("package\\s+([\\w.]+)").matcher(text);
                if (pkg.find() && !dev.logast.ShapeExport.publicName(pkg.group(1), List.of())) {
                    text = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*").matcher(text).replaceAll(m -> mapId.apply(m.group()));
                    name = mapId.apply(name);
                }
                Path dst = out.resolve("src").resolve(pkg.group(1)).resolve(name + ".java");
                Files.createDirectories(dst.getParent());
                Files.writeString(dst, text);
                args.add(dst.toString());
            }
        }
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, err, args.toArray(String[]::new))).as(err.toString()).isZero();
        return out.resolve("classes");
    }

    private static void compile(Path root, String encoding, Path... classes) throws IOException {
        List<String> sources;
        try (Stream<Path> s = Files.walk(root.resolve("src/main/java"))) {
            sources = new ArrayList<>(s.filter(p -> p.toString().endsWith(".java")).map(Path::toString).toList());
        }
        if (Files.isDirectory(root.resolve("build/generated"))) {
            // generated sources the build adds to the main source set (#51)
            try (Stream<Path> s = Files.walk(root.resolve("build/generated"))) {
                s.filter(p -> p.toString().endsWith(".java")).map(Path::toString).forEach(sources::add);
            }
        }
        List<String> cp = new ArrayList<>(Files.readAllLines(Path.of(CLASSPATH)).stream().filter(l -> !l.isBlank()).toList());
        Stream.of(classes).map(Path::toString).forEach(cp::add);
        List<String> args = new ArrayList<>(List.of("-encoding", encoding, "-d", root.resolve("out").toString(), "-proc:full",
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
