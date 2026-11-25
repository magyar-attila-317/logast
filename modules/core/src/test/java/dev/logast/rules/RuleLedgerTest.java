package dev.logast.rules;

import dev.logast.Analysis;
import dev.logast.Fixtures;
import dev.logast.Traffic;
import dev.logast.orders.WorkOrder;
import dev.logast.parse.ParsedProject;
import dev.logast.parse.ProjectParser;
import dev.logast.policy.Policy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #58 a + b: every rule id is tested both ways, and switching it off changes a result.
 *
 * <p><b>Firing case</b>: the rule is reported in one of the {@link #corpora() corpora} (core scans of parsed projects,
 * plus {@code logast verify} on captured events for the RUNTIME_* rules), or appears in an e2e snapshot
 * ({@code FINDINGS.txt}, the applied ids in {@code OUTPUT.txt}, {@code .logast/reports/apply-trace.txt}; in the
 * scenarios that snapshot only the service (#110) the findings list and {@code RULES.txt}).
 *
 * <p><b>Must-not-fire case</b>, the smallest version that still means something:
 * <ul>
 *   <li>the rule's <em>own fix</em> is a shape it must not fire on: an AUTO order of the fixture (RoundTripTest applies
 *       them all and requires that only report-only orders remain), or an order applied in an e2e snapshot (the run
 *       rescans it: no {@code CHECK FAILED}, pass 2 and a second apply apply nothing);</li>
 *   <li>for report-only rules, which have no fix: a near miss in the fixture marked {@code // expect-not: RULE} on the
 *       line the rule would report if it fired; checked here;</li>
 *   <li>or a named unit test that asserts the rule stays silent on a near miss ({@link #SILENT_IN_UNIT_TEST}); for
 *       project-wide findings that is the project with the note's remedy applied ({@link RemedyTest}, #58 c).</li>
 * </ul>
 * The near misses are #58 f (negative corpus); rules still without one are in {@link #NO_MUST_NOT_FIRE_CASE}.
 *
 * <p><b>Knock-out</b>: each rule reported in a corpus is switched off via the policy ({@code rules: {X: {enabled: false}}})
 * and the corpus rescanned: no order of the rule may remain, nor list it under "also fixes", and the result must differ. Rescans only rebuild the
 * analysis of an already parsed project (no per-rule e2e run). Rules that cannot be knocked out this way are in
 * {@link #NO_KNOCKOUT}.
 *
 * <p>Every allow-list entry names the issue that tracks it, and an entry that is no longer needed fails the test.
 */
class RuleLedgerTest {
    private static final Path REPO = Fixtures.root().toAbsolutePath().getParent().getParent().getParent();   // testing/fixtures/sample-app
    private static final Path E2E = REPO.resolve("testing/e2e");
    private static final Pattern EXPECT_NOT = Pattern.compile("// expect-not: ([A-Z_, ]+)");
    private static final Pattern ISSUE = Pattern.compile("#\\d+");

    /** Rules with no firing case anywhere. */
    static final Map<String, String> NO_FIRING_CASE = Map.of();

    /** Unit tests (Class#method) that assert the rule stays silent on a near miss. */
    static final Map<String, String> SILENT_IN_UNIT_TEST = Map.ofEntries(
            Map.entry(Rules.LOG_VOLUME, "VolumeTest#volumeNeedsProvidedTrafficAndUsesTheBudget"),   // no traffic, below budget
            Map.entry(Rules.MDC_VALUE_UNVALIDATED, "UnvalidatedMdcValueTest#callerChosenMdcValuesNeedACheck"),   // a pattern, a UUID parse, a generated id
            Map.entry(Rules.LOG_IN_LOOP, "LoopSizeTest#infoPerItemDependsOnTheLoopSize"),            // declared small, fixed loops
            Map.entry(Rules.RUNTIME_TRACE_LOST, "RuntimeVerifierTest#sameThreadOrNoPriorContextIsNotALoss"),
            Map.entry(Rules.RUNTIME_MDC_LOST, "RuntimeVerifierTest#sameThreadOrNoPriorContextIsNotALoss"),
            Map.entry(Rules.RUNTIME_NULL_VALUE, "RuntimeVerifierTest#sameThreadOrNoPriorContextIsNotALoss"),
            // #58 c: project-wide findings, so the near miss is the whole project with the note's remedy applied
            Map.entry(Rules.KAFKA_NO_ERROR_HANDLER, "rules/RemedyTest#kafkaListenerWithACommonErrorHandlerBean"),
            // #291: a proposal, never applied: the near miss is the service that decided (the property set)
            Map.entry(Rules.ERROR_RESPONSE_UNTRACED, "rules/RemedyTest#theTraceIdInErrorBodiesIsDecidedByTheProperty"),
            Map.entry(Rules.TRACING_SAMPLING, "rules/RemedyTest#samplingIsWrittenOnlyWhenThePolicyChoosesIt"),   // #297: the service sets its own
            Map.entry(Rules.PROBLEM_DETAILS_DISABLED, "rules/RemedyTest#problemDetailsByPropertyOrByAdvice"),
            Map.entry(Rules.REQUEST_NOT_VALIDATED, "rules/RemedyTest#requestBodyValidatedWithValid"),
            Map.entry(Rules.SCHEDULED_WITHOUT_LOCK, "rules/RemedyTest#scheduledJobWithALockOrOnASingleInstance"),
            Map.entry(Rules.SCHEDULER_SINGLE_THREAD, "rules/RemedyTest#scheduledJobsWithAPoolOfTheirOwn"),
            Map.entry(Rules.SHUTDOWN_NOT_GRACEFUL, "rules/RemedyTest#gracefulShutdownLongerThanTheWork"),
            Map.entry(Rules.HEALTH_PROBE_MISCONFIGURED, "rules/RemedyTest#healthProbesOnAndLivenessKeptToTheInstance"),
            Map.entry(Rules.READINESS_WITHOUT_DATABASE, "rules/RemedyTest#readinessWithTheDatabaseOrDecidedAgainst"),
            Map.entry(Rules.CACHE_UNBOUNDED, "rules/RemedyTest#cachesWithASizeBound"),
            Map.entry(Rules.RABBIT_NO_ERROR_HANDLER, "rules/RemedyTest#rabbitListenerRetriedOrRejectedForGood"),
            Map.entry(Rules.RETRY_IN_TRANSACTION, "rules/RemedyTest#retryWrapsTheWholeTransaction"),
            Map.entry(Rules.RETRY_RECOMMENDED, "rules/RemedyTest#idempotentCallRetriedOnItsPath"),
            Map.entry(Rules.NESTED_RETRY, "rules/RemedyTest#retryAtOneLayerOnly"),
            Map.entry(Rules.RETRY_BYPASSED, "rules/RemedyTest#retryGoesWhereTheProxySeesTheCall"),
            Map.entry(Rules.LOG_KEY_NESTING_CONFLICT, "rules/RemedyTest#aFieldAndItsDottedSubFieldConflict"),
            Map.entry(Rules.AUDIT_SECURITY_EVENTS, "rules/RemedyTest#securityEventsRecordedDurably"),
            Map.entry(Rules.TRACING_EXPORT_UNCONFIGURED, "rules/RemedyTest#tracingExportWithAnEndpointPerEnvironment"),
            // #92: the audit pack's report-only rules, each with the remedy its note names
            Map.entry(Rules.AUDIT_LOGGER_UNROUTED, "rules/RemedyTest#auditLoggerRoutedApart"),
            Map.entry(Rules.AUDIT_BEFORE_COMMIT, "rules/RemedyTest#auditRecordWrittenWhenTheOutcomeIsKnown"),
            Map.entry(Rules.AUDIT_RECORD_SECRET, "rules/RemedyTest#auditRecordWithoutSecrets"),
            Map.entry(Rules.AUDIT_ACTOR_LOST_ASYNC, "rules/RemedyTest#auditActorReadBeforeTheHandOver"),
            Map.entry(Rules.TRACE_ID_NOT_LOGGED, "rules/RemedyTest#customLogPatternWithTheTraceId"),
            Map.entry(Rules.SSE_BROADCAST_CONTEXT, "rules/RemedyTest#broadcastLinesNameTheSubscriber"));

    /** Report-only rules (no fix that could serve as the negative) without a marked near miss yet. */
    static final Map<String, String> NO_MUST_NOT_FIRE_CASE = negativeCorpus();

    /** Rules the core knock-out cannot test: where they fire instead, or why switching them off changes nothing. */
    static final Map<String, String> NO_KNOCKOUT = Map.ofEntries(
            Map.entry(Rules.AUDIT_SECURITY_EVENTS, "#92: needs Spring Security on the classpath, which the corpora do not have: fires in "
                    + "its own e2e scenario (expected-audit-pack, stand-in jar) and in RemedyTest#securityEventsRecordedDurably"),
            Map.entry(Rules.AUDIT_BEFORE_COMMIT, "#92: no corpus has an audit call inside a transaction: fires in its own e2e scenario "
                    + "(expected-audit-records) and in RemedyTest#auditRecordWrittenWhenTheOutcomeIsKnown"),
            Map.entry(Rules.AUDIT_RECORD_SECRET, "#92: no corpus passes a secret to an audit call: fires in its own e2e scenario "
                    + "(expected-audit-records) and in RemedyTest#auditRecordWithoutSecrets"),
            Map.entry(Rules.AUDIT_ACTOR_LOST_ASYNC, "#92: needs Spring Security's SecurityContextHolder, which the corpora do not have: "
                    + "fires in its own e2e scenario (expected-audit-records, stand-in jar) and in "
                    + "RemedyTest#auditActorReadBeforeTheHandOver"),
            Map.entry(Rules.BUSINESS_EVENT_TIMESTAMP, "#58: fires in its own e2e scenario (expected-business-timestamp); no corpus has the shape"),
            Map.entry(Rules.MESSAGE_SUGGESTED, "#58: fires on the // logast:wording marker an apply leaves behind (e2e snapshots)"),
            Map.entry(Rules.METRIC_CATALOG, "#58: fires once metric decisions are applied (e2e expected-decided)"),
            Map.entry(Rules.TRACE_ID_NOT_LOGGED, "#89: only without the logging pack (SETUP_LOGBACK_XML covers the file there), and no "
                    + "corpus has a custom logback pattern without the trace id: fires in its own e2e scenario "
                    + "(expected-tracing-depth), which also scans it switched off"),
            Map.entry(Rules.REACTIVE_CONTEXT_LOSS, "#14: needs an entrypoint returning a Mono/Flux, which no corpus has: fires and is "
                    + "applied in its own e2e scenario (expected-reactive), with the property, tracing alone and logging alone"),
            Map.entry(Rules.MDC_KEY_NOT_IN_LOG_OUTPUT, "#296: needs a JSON encoder with an includeMdcKeyName list, which no "
                    + "corpus has: fires and is applied in its own e2e scenario (expected-adoption-shapes) and in "
                    + "LogOutputAllowlistTest#keysTheCodeWritesAreAddedToTheEncodersList"),
            Map.entry(Rules.SETUP_OTEL_STARTER, "#58: needs a Boot 3 build with a conflicting OpenTelemetry setup (e2e expected-boot3-conflict)"),
            Map.entry(Rules.RETRY_RECOMMENDED, "#105: no corpus reaches an idempotent client call from an entrypoint without a "
                    + "retry: fires in its own e2e scenario (expected-retry-coverage) and in RemedyTest#idempotentCallRetriedOnItsPath"),
            Map.entry(Rules.LOG_ID_MISSING, "#120: no corpus has an outcome / error line in the house format that leaves "
                    + "out an id its method has, before a migration: fires in its own e2e scenario (expected-log-id-missing) "
                    + "and in the main migration (OrderNotifications)"),
            Map.entry(Rules.LOG_KEY_NESTING_CONFLICT, "#18: no corpus logs x next to x.y before a migration: fires in its "
                    + "own e2e scenario (expected-key-nesting-conflict) and in RemedyTest#aFieldAndItsDottedSubFieldConflict"),
            Map.entry(Rules.RETRY_BYPASSED, "#109: no corpus self-invokes a retried method: fires in "
                    + "RemedyTest#retryGoesWhereTheProxySeesTheCall"),
            Map.entry(Rules.NESTED_RETRY, "#105: no corpus has a retried method calling another one: fires in its own e2e scenario "
                    + "(expected-retry-coverage) and in RemedyTest#retryAtOneLayerOnly"),
            Map.entry(Rules.SSE_BROADCAST_CONTEXT, "#17: no corpus broadcasts to stored SseEmitters: fires in its own e2e scenario "
                    + "(expected-sse-broadcast), which also scans it switched off"));

    private static Map<String, String> negativeCorpus(String... rules) {
        Map<String, String> out = new TreeMap<>();
        for (String r : rules) {
            out.put(r, "#58 f (negative corpus): report-only, no near miss marked yet");
        }
        return out;
    }

    // ---- corpora ----------------------------------------------------------------------------------------------------

    /** Captured log events of a test (as in RuntimeVerifierTest): context lost on another thread, a null value. */
    private static final List<RuntimeVerifier.Event> EVENTS = List.of(
            new RuntimeVerifier.Event("[engine:junit-jupiter]/[class:com.x.FlowTest]/[method:flow()]", "main",
                    "Sending confirmation for order {}", "Sending confirmation for order o-1",
                    "com.example.shop.notification.NotificationService", 25, Map.of("traceId", "abc", "order.id", "o-1")),
            new RuntimeVerifier.Event("[engine:junit-jupiter]/[class:com.x.FlowTest]/[method:flow()]", "pool-1",
                    "Sending mail to {}", "Sending mail to null", "com.example.shop.notification.Mailer", 12, Map.of()));

    /**
     * A parsed project plus the policy and traffic it is scanned with (or the captured events it is verified against);
     * a rescan only rebuilds the analysis.
     */
    record Corpus(String name, ParsedProject project, Policy policy, Traffic traffic, List<RuntimeVerifier.Event> events) {
        List<WorkOrder> orders(Policy p) {
            Analysis a = Analysis.of(project, p).withTraffic(traffic);
            return events == null ? Rules.scan(a).orders() : RuntimeVerifier.verify(a, events).orders();
        }

        List<WorkOrder> orders() {
            return orders(policy);
        }
    }

    private static List<Corpus> corpora;

    static synchronized List<Corpus> corpora() {
        if (corpora == null) {
            ParsedProject fixture = Fixtures.analysis().project;
            Policy policy = Fixtures.policy();
            Path before = E2E.resolve("before");
            Policy e2ePolicy;
            try {
                e2ePolicy = Policy.resolve(before, null);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            corpora = List.of(
                    new Corpus("fixture", fixture, policy, Fixtures.traffic(), null),
                    // LOG_VOLUME needs real rates (as in VolumeTest)
                    new Corpus("fixture with traffic", fixture, policy,
                            Traffic.of(Map.of("POST /orders", 500_000d), "ledger"), null),
                    // LOG_IN_LOOP: the fixture's policy lowers info in loops to debug instead (as in VolumeTest)
                    new Corpus("fixture, info in loops allowed", fixture,
                            policy.withLevels(new Policy.Levels(null, null, false, null, null)), Fixtures.traffic(), null),
                    new Corpus("fixture, verified against captured events", fixture, policy, Fixtures.traffic(), EVENTS),
                    // the e2e input, read-only and without Lombok: only used to see which rules fire and switch off
                    new Corpus("e2e/before", new ProjectParser().parse(before, Fixtures.classpath()), e2ePolicy,
                            Traffic.none(), null));
        }
        return corpora;
    }

    // ---- the tests --------------------------------------------------------------------------------------------------

    @Test
    void everyRuleHasAFiringAndAMustNotFireCase() throws IOException {
        Map<String, Set<String>> firing = new TreeMap<>();
        Map<String, Set<String>> mustNotFire = new TreeMap<>();
        BiConsumer<String, String> fires = (rule, where) -> firing.computeIfAbsent(rule, k -> new TreeSet<>()).add(where);
        BiConsumer<String, String> silent = (rule, where) -> mustNotFire.computeIfAbsent(rule, k -> new TreeSet<>()).add(where);
        List<String> problems = new ArrayList<>();

        for (Corpus c : corpora()) {
            List<WorkOrder> orders = c.orders();
            mentioned(orders).forEach(rule -> fires.accept(rule, c.name()));
            if (c.name().equals("fixture")) {
                orders.stream().filter(o -> !o.reportOnly() && !o.edits().isEmpty())
                        .forEach(o -> silent.accept(o.rule(), "its fix, rescanned by RoundTripTest"));
                checkNearMisses(orders, silent, problems);
            }
        }
        snapshots(fires, silent);
        for (Map.Entry<String, String> e : SILENT_IN_UNIT_TEST.entrySet()) {
            String[] test = e.getValue().split("#");
            Path src = REPO.resolve("modules/core/src/test/java/dev/logast/" + test[0] + ".java");
            if (Files.exists(src) && Files.readString(src).contains("void " + test[1] + "(")) {
                silent.accept(e.getKey(), e.getValue());
            } else {
                problems.add("SILENT_IN_UNIT_TEST: no test " + e.getValue());
            }
        }

        Set<String> all = ruleIds();
        checkAllowList("NO_FIRING_CASE", NO_FIRING_CASE, firing.keySet(), all, problems);
        checkAllowList("NO_MUST_NOT_FIRE_CASE", NO_MUST_NOT_FIRE_CASE, mustNotFire.keySet(), all, problems);
        for (String rule : all) {
            if (!firing.containsKey(rule) && !NO_FIRING_CASE.containsKey(rule)) {
                problems.add(rule + ": no firing case (no corpus or e2e snapshot reports it)");
            }
            if (!mustNotFire.containsKey(rule) && !NO_MUST_NOT_FIRE_CASE.containsKey(rule)) {
                problems.add(rule + ": no must-not-fire case (no applied fix, no // expect-not: near miss in the fixture, "
                        + "no unit test in SILENT_IN_UNIT_TEST)");
            }
        }
        System.out.println("rule ledger, firing: " + firing);
        System.out.println("rule ledger, must not fire: " + mustNotFire);
        assertThat(problems).as("rule ledger (#58 a); see the class comment for what counts").isEmpty();
    }

    @Test
    void switchingOffAnyRuleChangesTheScan() {
        Map<String, String> knockedOut = new TreeMap<>();    // rule → corpus where switching it off changed the result
        Map<String, String> failed = new TreeMap<>();        // rule → why it did not (last corpus tried)
        for (Corpus c : corpora()) {
            List<WorkOrder> base = c.orders();
            Set<String> baseline = fingerprint(base);
            for (String rule : mentioned(base)) {
                if (knockedOut.containsKey(rule)) {
                    continue;   // one corpus is enough
                }
                List<WorkOrder> off = c.orders(disable(c.policy(), rule));
                if (off.stream().anyMatch(o -> o.rule().equals(rule))) {
                    failed.put(rule, c.name() + ": still reported with the rule switched off");
                } else if (mentioned(off).contains(rule)) {   // #75: another rule's rewrite folds it in or lists it
                    failed.put(rule, c.name() + ": still listed under 'also fixes' with the rule switched off");
                } else if (fingerprint(off).equals(baseline)) {
                    failed.put(rule, c.name() + ": switching it off changes nothing");
                } else {
                    knockedOut.put(rule, c.name());
                    failed.remove(rule);
                }
            }
        }
        List<String> problems = new ArrayList<>();
        Set<String> all = ruleIds();
        checkAllowList("NO_KNOCKOUT", NO_KNOCKOUT, knockedOut.keySet(), all, problems);
        for (String rule : all) {
            if (!knockedOut.containsKey(rule) && !NO_KNOCKOUT.containsKey(rule)) {
                problems.add(rule + ": " + failed.getOrDefault(rule, "fires in no corpus, so switching it off was never tested"));
            }
        }
        System.out.println("knock-out: " + knockedOut);
        assertThat(problems).as("knock-out (#58 b)").isEmpty();
    }

    // ---- evidence ---------------------------------------------------------------------------------------------------

    /** Every rule an order reports: its own, the ones it also fixes, the ones at its locations. */
    static Set<String> mentioned(List<WorkOrder> orders) {
        Set<String> out = new TreeSet<>();
        for (WorkOrder o : orders) {
            out.add(o.rule());
            out.addAll(o.fixes());
            o.locations().forEach(l -> out.add(l.rule()));
        }
        return out;
    }

    /** What an e2e snapshot would show of a scan: ids, rules, positions, what each order fixes and its edits. */
    static Set<String> fingerprint(List<WorkOrder> orders) {
        Set<String> out = new TreeSet<>();
        for (WorkOrder o : orders) {
            out.add(o.id() + " " + o.rule() + " " + o.file() + ":" + o.line() + " " + new TreeSet<>(o.fixes()) + " "
                    + o.locations() + " " + o.edits() + " " + o.title());
        }
        return out;
    }

    static Policy disable(Policy p, String rule) {
        Map<String, Policy.RuleConfig> rules = new HashMap<>(p.rules());
        rules.put(rule, new Policy.RuleConfig(false, null));
        return new Policy(p.parents(), p.glossary(), p.mdc(), p.pii(), p.entrypoints(), p.logging(), p.support(), rules,
                p.vocabulary(), p.areas(), p.phrasing(), p.levels(), p.packs(), p.overrides(), p.verify(), p.metrics(),
                p.resilience(), p.audit(), p.reporting(), p.queries(), p.tracing(), p.warnings());
    }

    /** {@code // expect-not: RULE} lines of the fixture: the rule must not report that line. */
    private static void checkNearMisses(List<WorkOrder> orders, BiConsumer<String, String> silent, List<String> problems)
            throws IOException {
        Set<String> found = new HashSet<>();
        for (WorkOrder o : orders) {
            found.add(o.file() + ":" + o.line() + " " + o.rule());
            o.fixes().forEach(f -> found.add(o.file() + ":" + o.line() + " " + f));
            o.locations().forEach(l -> found.add(l.file() + ":" + l.line() + " " + l.rule()));
        }
        Path root = Fixtures.root();
        Set<String> all = ruleIds();
        try (Stream<Path> files = Files.walk(root.resolve("src/main/java"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                List<String> lines = Files.readAllLines(p);
                String rel = root.relativize(p).toString().replace('\\', '/');
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = EXPECT_NOT.matcher(lines.get(i));
                    if (!m.find()) {
                        continue;
                    }
                    for (String rule : m.group(1).split(",")) {
                        rule = rule.trim();
                        String at = rel + ":" + (i + 1);
                        if (!all.contains(rule)) {
                            problems.add(at + ": // expect-not: names an unknown rule " + rule);
                        } else if (found.contains(at + " " + rule)) {
                            problems.add(at + ": " + rule + " fires on a line marked // expect-not");
                        } else {
                            silent.accept(rule, "near miss " + at);
                        }
                    }
                }
            }
        }
    }

    private static final Pattern FINDING = Pattern.compile("^[A-Z]{3}-[0-9a-f]{6}\\S*\\s+\\S+\\s+(?:AUTO|NEEDS TEXT|REPORT-ONLY)\\s+([A-Z_]+)\\s");
    private static final Pattern INDEX = Pattern.compile("^(?:AUTO|NEEDS TEXT|REPORT-ONLY) ([A-Z_]+) \\S+:\\d+$");
    private static final Pattern RULES_LINE = Pattern.compile("^(applied|rescan|also) ([A-Z_]+)$");
    private static final Pattern ALSO_FIXES = Pattern.compile("^\\s+also fixes: ([A-Z_]+)\\s");
    private static final Pattern TRACED = Pattern.compile("([A-Z][A-Z_]+) [A-Z]{3}-[0-9a-f]{6}");
    private static final Pattern ID = Pattern.compile("\\b([A-Z]{3})-[0-9a-f]{6}\\b");

    /** e2e snapshots, each compared file by file by E2eGoldenTest: what they report and what they applied. */
    private static void snapshots(BiConsumer<String, String> fires, BiConsumer<String, String> silent) throws IOException {
        Map<String, String> ruleOfPrefix = new HashMap<>();
        OrderBuilder.PREFIX.forEach((rule, prefix) -> assertThat(ruleOfPrefix.put(prefix, rule)).as("prefix " + prefix).isNull());
        List<Path> dirs;
        try (Stream<Path> s = Files.list(E2E)) {
            dirs = s.filter(d -> d.getFileName().toString().startsWith("expected")).sorted().toList();
        }
        assertThat(dirs).as("e2e snapshots under " + E2E).isNotEmpty();
        for (Path dir : dirs) {
            String name = "e2e " + dir.getFileName();
            for (String file : List.of("FINDINGS.txt", "OUTPUT.txt", ".logast/reports/apply-trace.txt", ".logast/reports/last-apply.txt", "RULES.txt")) {
                Path f = dir.resolve(file);
                if (!Files.exists(f)) {
                    continue;
                }
                for (String line : Files.readAllLines(f)) {
                    // the snapshot of most scenarios (#110): findings as "KIND RULE file:line", RULES.txt as "applied RULE" etc.
                    Matcher r = RULES_LINE.matcher(line);
                    if (r.matches()) {
                        fires.accept(r.group(2), name + "/" + file);
                        if (r.group(1).equals("applied")) {
                            silent.accept(r.group(2), "its fix, applied and rescanned in " + name);
                        }
                    }
                    r = INDEX.matcher(line);
                    if (r.find()) {
                        fires.accept(r.group(1), name + "/" + file);
                    }
                    Matcher m = FINDING.matcher(line);
                    if (m.find()) {
                        fires.accept(m.group(1), name + "/" + file);
                    }
                    m = ALSO_FIXES.matcher(line);
                    if (m.find()) {
                        fires.accept(m.group(1), name + "/" + file);
                    }
                    if (line.contains("applying [") || line.contains("rescan: new [")) {
                        boolean applied = line.contains("applying [");
                        for (m = TRACED.matcher(line); m.find(); ) {
                            fires.accept(m.group(1), name + "/" + file);
                            if (applied) {
                                silent.accept(m.group(1), "its fix, applied and rescanned in " + name);
                            }
                        }
                    }
                    if (line.startsWith("applied (")) {
                        for (m = ID.matcher(line); m.find(); ) {
                            String rule = ruleOfPrefix.get(m.group(1));
                            if (rule != null) {
                                fires.accept(rule, name + "/" + file);
                                silent.accept(rule, "its fix, applied and rescanned in " + name);
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    /** The rule ids: every String constant of {@link Rules}, which must match {@link Rules#all()}. */
    static Set<String> ruleIds() {
        Set<String> ids = new TreeSet<>();
        for (Field f : Rules.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                try {
                    ids.add((String) f.get(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        assertThat(ids).as("Rules constants vs Rules.all()").isEqualTo(Rules.all());
        return ids;
    }

    private static void checkAllowList(String name, Map<String, String> list, Set<String> covered, Set<String> all,
                                       List<String> problems) {
        list.forEach((rule, reason) -> {
            if (!all.contains(rule)) {
                problems.add(name + ": unknown rule " + rule);
            } else if (covered.contains(rule)) {
                problems.add(name + ": " + rule + " is covered now, remove it from the list");
            }
            if (!ISSUE.matcher(reason).find()) {
                problems.add(name + ": the entry for " + rule + " names no issue");
            }
        });
    }
}
