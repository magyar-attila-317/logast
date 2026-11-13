package dev.logast.rules;

import dev.logast.Analysis;
import dev.logast.orders.Edit;
import dev.logast.orders.ScanResult;
import dev.logast.orders.WorkOrder;
import dev.logast.parse.Hashing;
import dev.logast.policy.Policy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class Rules {
    public static final String SWALLOWED_EXCEPTION = "SWALLOWED_EXCEPTION";
    public static final String LOST_THROWABLE = "LOST_THROWABLE";
    public static final String LOG_AND_RETHROW = "LOG_AND_RETHROW";
    public static final String STRING_CONCAT_LOG = "STRING_CONCAT_LOG";
    public static final String LOG_FORMAT = "LOG_FORMAT";
    public static final String GLOSSARY_VIOLATION = "GLOSSARY_VIOLATION";
    public static final String PII_IN_LOG = "PII_IN_LOG";
    public static final String MDC_NOT_CLOSEABLE = "MDC_NOT_CLOSEABLE";
    public static final String MANUAL_TRACE_MDC = "MANUAL_TRACE_MDC";
    public static final String MISSING_DOMAIN_MDC = "MISSING_DOMAIN_MDC";
    public static final String ENTRYPOINT_NOT_OBSERVED = "ENTRYPOINT_NOT_OBSERVED";
    public static final String ENTRYPOINT_SILENT = "ENTRYPOINT_SILENT";
    public static final String ASYNC_CONTEXT_LOSS = "ASYNC_CONTEXT_LOSS";
    public static final String SETUP_OTEL_STARTER = "SETUP_OTEL_STARTER";
    public static final String SETUP_SUPPORT_STARTER = "SETUP_SUPPORT_STARTER";
    public static final String SETUP_LOGBACK_XML = "SETUP_LOGBACK_XML";
    public static final String SETUP_STRUCTURED_LOGGING = "SETUP_STRUCTURED_LOGGING";
    public static final String MESSAGE_PHRASING = "MESSAGE_PHRASING";
    public static final String MDC_KEY_ALIAS = "MDC_KEY_ALIAS";
    public static final String LOG_KEY_STYLE = "LOG_KEY_STYLE";
    public static final String REPEATED_LOG_VALUE = "REPEATED_LOG_VALUE";
    public static final String MDC_CLEAR = "MDC_CLEAR";
    public static final String MDC_NESTED_KEY = "MDC_NESTED_KEY";
    public static final String MDC_NOT_OWNER = "MDC_NOT_OWNER";
    public static final String JOB_OUTCOME_MISSING = "JOB_OUTCOME_MISSING";
    public static final String SKIP_UNLOGGED = "SKIP_UNLOGGED";
    public static final String STATE_CHANGE_UNLOGGED = "STATE_CHANGE_UNLOGGED";
    public static final String BATCH_SUMMARY_MISSING = "BATCH_SUMMARY_MISSING";
    public static final String DB_OUTCOME_UNLOGGED = "DB_OUTCOME_UNLOGGED";
    public static final String SETUP_SERVICE_IDENTITY = "SETUP_SERVICE_IDENTITY";
    public static final String LIFECYCLE_UNEVEN = "LIFECYCLE_UNEVEN";
    public static final String ROW_BY_ROW_WRITE = "ROW_BY_ROW_WRITE";
    public static final String METRIC_OUTCOME_UNMEASURED = "METRIC_OUTCOME_UNMEASURED";
    public static final String MESSAGE_SUGGESTED = "MESSAGE_SUGGESTED";
    public static final String LOG_OBJECT_ARGUMENT = "LOG_OBJECT_ARGUMENT";
    public static final String LOG_COLLECTION_ARGUMENT = "LOG_COLLECTION_ARGUMENT";
    public static final String LOG_HELPER = "LOG_HELPER";
    public static final String MESSAGE_VAGUE = "MESSAGE_VAGUE";
    public static final String LOG_KEY_VALUE_IN_MDC = "LOG_KEY_VALUE_IN_MDC";
    public static final String AREA_MDC_MISSING = "AREA_MDC_MISSING";
    public static final String LOG_LEVEL = "LOG_LEVEL";
    public static final String EXCEPTION_LOGGED_TWICE = "EXCEPTION_LOGGED_TWICE";
    public static final String HTTP_CLIENT_UNTRACED = "HTTP_CLIENT_UNTRACED";
    public static final String TRACING_SAMPLING = "TRACING_SAMPLING";
    public static final String SCHEDULED_TASK_UNTRACED = "SCHEDULED_TASK_UNTRACED";
    public static final String KAFKA_OBSERVATION_DISABLED = "KAFKA_OBSERVATION_DISABLED";
    public static final String JOB_LAUNCH_UNTRACED = "JOB_LAUNCH_UNTRACED";
    public static final String RUNTIME_TRACE_LOST = "RUNTIME_TRACE_LOST";
    public static final String RUNTIME_MDC_LOST = "RUNTIME_MDC_LOST";
    public static final String RUNTIME_NULL_VALUE = "RUNTIME_NULL_VALUE";
    public static final String LOG_IN_LOOP = "LOG_IN_LOOP";
    public static final String LOG_VOLUME = "LOG_VOLUME";
    public static final String METRIC_NAMING = "METRIC_NAMING";
    public static final String METRIC_HIGH_CARDINALITY_TAG = "METRIC_HIGH_CARDINALITY_TAG";
    public static final String METRIC_DIRECT_REGISTRY = "METRIC_DIRECT_REGISTRY";
    public static final String METRIC_TAG_KEY = "METRIC_TAG_KEY";
    public static final String METRIC_TAG_VALUE = "METRIC_TAG_VALUE";
    public static final String METRIC_REGISTERED_PER_CALL = "METRIC_REGISTERED_PER_CALL";
    public static final String METRIC_GAUGE_WEAK_REF = "METRIC_GAUGE_WEAK_REF";
    public static final String BUSINESS_EVENT_UNMEASURED = "BUSINESS_EVENT_UNMEASURED";
    public static final String METRIC_TAG_KEYS_MISMATCH = "METRIC_TAG_KEYS_MISMATCH";
    // resilience
    public static final String HTTP_CLIENT_NO_TIMEOUT = "HTTP_CLIENT_NO_TIMEOUT";
    public static final String KAFKA_NO_ERROR_HANDLER = "KAFKA_NO_ERROR_HANDLER";
    public static final String UNBOUNDED_EXECUTOR = "UNBOUNDED_EXECUTOR";
    public static final String BLOCKING_WAIT_UNBOUNDED = "BLOCKING_WAIT_UNBOUNDED";
    // errors
    public static final String ERROR_DETAILS_EXPOSED = "ERROR_DETAILS_EXPOSED";
    public static final String UNMAPPED_EXCEPTION = "UNMAPPED_EXCEPTION";
    public static final String CLIENT_ERROR_AS_SERVER_ERROR = "CLIENT_ERROR_AS_SERVER_ERROR";
    public static final String PROBLEM_DETAILS_DISABLED = "PROBLEM_DETAILS_DISABLED";
    // audit
    public static final String AUDIT_MISSING = "AUDIT_MISSING";
    public static final String AUDIT_SECURITY_EVENTS = "AUDIT_SECURITY_EVENTS";

    /** Rule → packs it belongs to. A rule runs when any of its packs is selected (policy.packs / --pack). */
    private static final Map<String, Set<String>> PACKS = new HashMap<>();

    static {
        for (String r : List.of(SWALLOWED_EXCEPTION, LOST_THROWABLE, LOG_AND_RETHROW, EXCEPTION_LOGGED_TWICE,
                STRING_CONCAT_LOG, LOG_FORMAT, GLOSSARY_VIOLATION, PII_IN_LOG, MDC_NOT_CLOSEABLE, MDC_KEY_ALIAS,
                MISSING_DOMAIN_MDC, AREA_MDC_MISSING, ENTRYPOINT_SILENT, MESSAGE_PHRASING, LOG_KEY_STYLE, LOG_LEVEL, REPEATED_LOG_VALUE,
                MDC_CLEAR, MDC_NESTED_KEY, MDC_NOT_OWNER, JOB_OUTCOME_MISSING, SKIP_UNLOGGED, STATE_CHANGE_UNLOGGED,
                BATCH_SUMMARY_MISSING, DB_OUTCOME_UNLOGGED, MESSAGE_SUGGESTED, LIFECYCLE_UNEVEN, LOG_OBJECT_ARGUMENT, LOG_COLLECTION_ARGUMENT, LOG_HELPER, MESSAGE_VAGUE, LOG_KEY_VALUE_IN_MDC,
                SETUP_LOGBACK_XML, SETUP_STRUCTURED_LOGGING, RUNTIME_MDC_LOST, RUNTIME_NULL_VALUE, LOG_IN_LOOP, LOG_VOLUME)) {
            PACKS.put(r, Set.of("logging"));
        }
        for (String r : List.of(ENTRYPOINT_NOT_OBSERVED, MANUAL_TRACE_MDC, SETUP_OTEL_STARTER, HTTP_CLIENT_UNTRACED,
                KAFKA_OBSERVATION_DISABLED, JOB_LAUNCH_UNTRACED, RUNTIME_TRACE_LOST, TRACING_SAMPLING)) {
            PACKS.put(r, Set.of("tracing"));
        }
        // context propagation matters to MDC (logging) and spans (tracing); the starter serves every pack
        PACKS.put(ASYNC_CONTEXT_LOSS, Set.of("logging", "tracing"));
        PACKS.put(SCHEDULED_TASK_UNTRACED, Set.of("logging", "tracing"));
        for (String r : List.of(METRIC_NAMING, METRIC_HIGH_CARDINALITY_TAG, METRIC_DIRECT_REGISTRY, METRIC_TAG_KEY,
                METRIC_TAG_VALUE, METRIC_REGISTERED_PER_CALL, METRIC_GAUGE_WEAK_REF, BUSINESS_EVENT_UNMEASURED, METRIC_TAG_KEYS_MISMATCH)) {
            PACKS.put(r, Set.of("metrics"));
        }
        for (String r : List.of(HTTP_CLIENT_NO_TIMEOUT, KAFKA_NO_ERROR_HANDLER, UNBOUNDED_EXECUTOR, BLOCKING_WAIT_UNBOUNDED, ROW_BY_ROW_WRITE)) {
            PACKS.put(r, Set.of("resilience"));
        }
        for (String r : List.of(ERROR_DETAILS_EXPOSED, UNMAPPED_EXCEPTION, CLIENT_ERROR_AS_SERVER_ERROR, PROBLEM_DETAILS_DISABLED)) {
            PACKS.put(r, Set.of("errors"));
        }
        for (String r : List.of(AUDIT_MISSING, AUDIT_SECURITY_EVENTS)) {
            PACKS.put(r, Set.of("audit"));
        }
        PACKS.put(SETUP_SUPPORT_STARTER, Set.of("logging", "tracing", "metrics"));
        PACKS.put(SETUP_SERVICE_IDENTITY, Set.of("logging", "tracing", "metrics"));
        PACKS.put(METRIC_OUTCOME_UNMEASURED, Set.of("metrics"));
    }

    /**
     * The pipeline step a rule belongs to (docs/pipeline-plan.md). {@code apply} runs the steps in this order on a
     * scratch copy, with a rescan in between, so each step sees the real result of the previous ones: first add what
     * is missing (setup, structure, lines), then polish (MDC, messages, exceptions, levels), then report.
     */
    public enum Step { SETUP, STRUCTURE, LINES, MDC, MESSAGES, EXCEPTIONS, LEVELS, REPORTS }

    private static final Map<String, Step> STEPS = new HashMap<>();

    static {
        for (String r : List.of(SETUP_SUPPORT_STARTER, SETUP_OTEL_STARTER, SETUP_LOGBACK_XML, SETUP_STRUCTURED_LOGGING,
                SETUP_SERVICE_IDENTITY, TRACING_SAMPLING, KAFKA_OBSERVATION_DISABLED, HTTP_CLIENT_UNTRACED, HTTP_CLIENT_NO_TIMEOUT,
                JOB_LAUNCH_UNTRACED)) {
            STEPS.put(r, Step.SETUP);
        }
        for (String r : List.of(MDC_NOT_CLOSEABLE, MDC_CLEAR, MDC_NOT_OWNER, ENTRYPOINT_NOT_OBSERVED, SCHEDULED_TASK_UNTRACED,
                ASYNC_CONTEXT_LOSS, BUSINESS_EVENT_UNMEASURED)) {
            STEPS.put(r, Step.STRUCTURE);
        }
        for (String r : List.of(ENTRYPOINT_SILENT, JOB_OUTCOME_MISSING, BATCH_SUMMARY_MISSING, SKIP_UNLOGGED, STATE_CHANGE_UNLOGGED,
                DB_OUTCOME_UNLOGGED, LIFECYCLE_UNEVEN, SWALLOWED_EXCEPTION, LOST_THROWABLE)) {
            STEPS.put(r, Step.LINES);
        }
        for (String r : List.of(MISSING_DOMAIN_MDC, REPEATED_LOG_VALUE, MDC_KEY_ALIAS, MDC_NESTED_KEY, AREA_MDC_MISSING)) {
            STEPS.put(r, Step.MDC);
        }
        for (String r : List.of(LOG_FORMAT, STRING_CONCAT_LOG, LOG_KEY_STYLE, GLOSSARY_VIOLATION, MESSAGE_PHRASING, PII_IN_LOG,
                LOG_OBJECT_ARGUMENT, LOG_COLLECTION_ARGUMENT, LOG_KEY_VALUE_IN_MDC, MESSAGE_SUGGESTED, MESSAGE_VAGUE, LOG_HELPER)) {
            STEPS.put(r, Step.MESSAGES);
        }
        for (String r : List.of(LOG_AND_RETHROW, EXCEPTION_LOGGED_TWICE)) {
            STEPS.put(r, Step.EXCEPTIONS);
        }
        for (String r : List.of(LOG_LEVEL, LOG_IN_LOOP, LOG_VOLUME)) {
            STEPS.put(r, Step.LEVELS);
        }
        for (String r : List.of(METRIC_NAMING, METRIC_HIGH_CARDINALITY_TAG, METRIC_DIRECT_REGISTRY, METRIC_TAG_KEY, METRIC_TAG_VALUE,
                METRIC_REGISTERED_PER_CALL, METRIC_GAUGE_WEAK_REF, METRIC_TAG_KEYS_MISMATCH, METRIC_OUTCOME_UNMEASURED,
                UNBOUNDED_EXECUTOR, KAFKA_NO_ERROR_HANDLER, BLOCKING_WAIT_UNBOUNDED, ROW_BY_ROW_WRITE, ERROR_DETAILS_EXPOSED,
                UNMAPPED_EXCEPTION, CLIENT_ERROR_AS_SERVER_ERROR, PROBLEM_DETAILS_DISABLED, AUDIT_MISSING, AUDIT_SECURITY_EVENTS,
                MANUAL_TRACE_MDC, RUNTIME_TRACE_LOST, RUNTIME_MDC_LOST, RUNTIME_NULL_VALUE)) {
            STEPS.put(r, Step.REPORTS);
        }
    }

    public static Step stepOf(String rule) {
        Step s = STEPS.get(rule);
        if (s == null) {
            throw new IllegalStateException("rule " + rule + " has no pipeline step (Rules.STEPS)");
        }
        return s;
    }

    public static Set<String> packsOf(String rule) {
        return PACKS.getOrDefault(rule, Set.of("logging"));
    }

    /** Enabled in the policy and one of its packs is selected. */
    public static boolean enabled(Policy policy, String rule) {
        return policy.ruleEnabled(rule) && packsOf(rule).stream().anyMatch(policy::packSelected);
    }

    private static final Map<String, String> SEVERITY = Map.ofEntries(
            Map.entry(SWALLOWED_EXCEPTION, "high"), Map.entry(PII_IN_LOG, "high"), Map.entry(ASYNC_CONTEXT_LOSS, "high"),
            Map.entry(SETUP_SUPPORT_STARTER, "high"), Map.entry(SETUP_OTEL_STARTER, "high"),
            Map.entry(ENTRYPOINT_NOT_OBSERVED, "high"),
            Map.entry(LOST_THROWABLE, "medium"), Map.entry(MISSING_DOMAIN_MDC, "medium"),
            Map.entry(ENTRYPOINT_SILENT, "medium"), Map.entry(MANUAL_TRACE_MDC, "medium"),
            Map.entry(SETUP_LOGBACK_XML, "medium"), Map.entry(LOG_AND_RETHROW, "medium"),
            Map.entry(SETUP_STRUCTURED_LOGGING, "medium"), Map.entry(MDC_NOT_CLOSEABLE, "low"),
            Map.entry(STRING_CONCAT_LOG, "low"), Map.entry(LOG_FORMAT, "low"), Map.entry(GLOSSARY_VIOLATION, "low"),
            Map.entry(MESSAGE_PHRASING, "low"), Map.entry(MDC_KEY_ALIAS, "medium"), Map.entry(LOG_KEY_STYLE, "low"), Map.entry(REPEATED_LOG_VALUE, "medium"),
            Map.entry(MDC_CLEAR, "high"), Map.entry(MDC_NOT_OWNER, "high"),
            Map.entry(JOB_OUTCOME_MISSING, "medium"), Map.entry(SKIP_UNLOGGED, "low"), Map.entry(STATE_CHANGE_UNLOGGED, "medium"),
            Map.entry(BATCH_SUMMARY_MISSING, "low"), Map.entry(DB_OUTCOME_UNLOGGED, "medium"),
            Map.entry(SETUP_SERVICE_IDENTITY, "medium"), Map.entry(LIFECYCLE_UNEVEN, "low"), Map.entry(ROW_BY_ROW_WRITE, "medium"), Map.entry(METRIC_OUTCOME_UNMEASURED, "low"), Map.entry(MESSAGE_SUGGESTED, "low"), Map.entry(MDC_NESTED_KEY, "medium"), Map.entry(LOG_OBJECT_ARGUMENT, "high"),
            Map.entry(LOG_COLLECTION_ARGUMENT, "medium"), Map.entry(LOG_HELPER, "medium"), Map.entry(MESSAGE_VAGUE, "low"),
            Map.entry(LOG_KEY_VALUE_IN_MDC, "low"),
            Map.entry(AREA_MDC_MISSING, "medium"), Map.entry(LOG_LEVEL, "medium"), Map.entry(EXCEPTION_LOGGED_TWICE, "medium"),
            Map.entry(HTTP_CLIENT_UNTRACED, "high"), Map.entry(TRACING_SAMPLING, "medium"),
            Map.entry(METRIC_TAG_KEY, "low"), Map.entry(METRIC_TAG_VALUE, "low"), Map.entry(METRIC_REGISTERED_PER_CALL, "low"),
            Map.entry(METRIC_GAUGE_WEAK_REF, "high"), Map.entry(BUSINESS_EVENT_UNMEASURED, "medium"),
            Map.entry(METRIC_TAG_KEYS_MISMATCH, "high"),
            Map.entry(HTTP_CLIENT_NO_TIMEOUT, "high"), Map.entry(KAFKA_NO_ERROR_HANDLER, "medium"),
            Map.entry(UNBOUNDED_EXECUTOR, "medium"), Map.entry(BLOCKING_WAIT_UNBOUNDED, "medium"),
            Map.entry(ERROR_DETAILS_EXPOSED, "high"), Map.entry(UNMAPPED_EXCEPTION, "medium"),
            Map.entry(CLIENT_ERROR_AS_SERVER_ERROR, "medium"), Map.entry(PROBLEM_DETAILS_DISABLED, "low"),
            Map.entry(AUDIT_MISSING, "medium"), Map.entry(AUDIT_SECURITY_EVENTS, "medium"), Map.entry(SCHEDULED_TASK_UNTRACED, "medium"), Map.entry(KAFKA_OBSERVATION_DISABLED, "high"), Map.entry(JOB_LAUNCH_UNTRACED, "high"),
            Map.entry(RUNTIME_TRACE_LOST, "high"), Map.entry(RUNTIME_MDC_LOST, "high"), Map.entry(RUNTIME_NULL_VALUE, "medium"),
            Map.entry(LOG_IN_LOOP, "low"), Map.entry(LOG_VOLUME, "medium"),
            Map.entry(METRIC_NAMING, "low"), Map.entry(METRIC_HIGH_CARDINALITY_TAG, "high"), Map.entry(METRIC_DIRECT_REGISTRY, "low"));

    private Rules() {
    }

    public static Set<String> all() {
        return new TreeSet<>(SEVERITY.keySet());
    }

    public static String severityOf(Policy policy, String rule) {
        return severity(policy, rule);
    }

    static String severity(Policy policy, String rule) {
        return policy.severity(rule, SEVERITY.getOrDefault(rule, "low"));
    }

    public static int rank(String severity) {
        return switch (severity) {
            case "high" -> 3;
            case "medium" -> 2;
            default -> 1;
        };
    }

    /** METRIC_OUTCOME_UNMEASURED: outcomes the logs report and no metric counts; a person or LLM picks the ones to count. */
    private static void outcomeGaps(RuleContext ctx) {
        var decisions = dev.logast.apply.MetricsFile.read(ctx.a.project.root());
        Map<String, Integer> seen = new HashMap<>();
        for (MetricsMap.Gap g : MetricsMap.of(ctx.a).gaps()) {
            MetricsMap.Unmeasured u = g.outcome();
            String anchor = u.file() + "|" + u.method() + "|" + u.message() + "|" + u.event();
            int n = seen.merge(anchor, 1, Integer::sum);   // the same message twice in one method: number the repeats
            OrderBuilder b = ctx.order(METRIC_OUTCOME_UNMEASURED, n == 1 ? anchor : anchor + "|" + n).at(u.file(), u.line())
                    .title("\"" + u.message() + "\" is logged but not counted (outcome=" + u.event() + ", frequency " + u.frequency() + ")")
                    .data("name", g.name()).data("outcome", u.event()).data("message", u.level().toUpperCase(java.util.Locale.ROOT) + " \"" + u.message() + "\"")
                    .data("frequency", u.frequency()).data("paths", u.paths()).data("tagAvailable", g.tagAvailable());
            String advice = "Decide in " + dev.logast.apply.MetricsFile.PATH + ": \"count\": \"counter\" (Outcomes.count(\"" + g.name()
                    + "\", \"" + u.event() + "\"))" + (g.tagAvailable() ? ", \"tag\" (Outcomes.tag(\"" + u.event() + "\") on the running timer)" : "")
                    + " or \"skip\"; then logast apply . --auto. Count it only if a dashboard or alert needs it.";
            String why = decide(ctx, b, g, decisions.get(b.id()));
            b.suggestion(why != null ? why + " " + advice : advice);
        }
    }

    /** Turns a metrics.json decision into edits; returns a problem to report, or null. */
    private static String decide(RuleContext ctx, OrderBuilder b, MetricsMap.Gap g, dev.logast.apply.MetricsFile.Decision d) {
        if (d == null) {
            return null;
        }
        dev.logast.parse.SourceFileInfo f = g.method().file();
        org.openrewrite.java.tree.J stmt = CoverageRules.enclosingStatement(g.method(), g.log().call());
        if (stmt == null) {
            return "Cannot place a line next to this log call automatically (not a plain statement).";
        }
        String text = f.text();
        int[] lines = JavaEdits.statementLines(text, stmt);
        String indent = Text.indentAt(text, dev.logast.parse.Pos.start(stmt));
        String name = d.name().isBlank() ? g.name() : d.name();
        String outcome = d.outcome().isBlank() ? g.outcome().event() : d.outcome();
        switch (d.count()) {
            case "skip" -> {
                int eol = lines[1] - 1;   // before the newline that ends the statement
                b.edit(dev.logast.orders.Edit.insert(f.relPath(), eol, " // logast:ignore " + METRIC_OUTCOME_UNMEASURED));
                return null;
            }
            case "counter", "tag" -> {
                if (!name.matches("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)*") || !outcome.matches("[a-z][a-z0-9_.-]*")) {
                    return "metrics.json: name and outcome must be lower case words joined by dots (got \"" + name + "\", \"" + outcome + "\").";
                }
                if (d.count().equals("tag") && !g.tagAvailable()) {
                    return "metrics.json: \"tag\" needs an observation running here and none is known; use \"counter\".";
                }
                if (!ctx.supportHas("Outcomes") || !ctx.a.project.probe().hasClass("io.micrometer.core.instrument.Metrics")) {
                    return "Needs Micrometer and a logging-support version with Outcomes.";
                }
                String call = d.count().equals("tag") ? "Outcomes.tag(\"" + outcome + "\");" : "Outcomes.count(\"" + name + "\", \"" + outcome + "\");";
                b.edit(dev.logast.orders.Edit.insert(f.relPath(), lines[1], indent + call + "\n"));
                b.edits(JavaEdits.imports(f, ctx.support("Outcomes")));
                ctx.dependOnStarter(b);
                return null;
            }
            default -> {
                return "metrics.json: \"count\" must be counter, tag or skip (got \"" + d.count() + "\").";
            }
        }
    }

    public static ScanResult scan(Analysis a) {
        RuleContext ctx = new RuleContext(a);
        new SetupRules(ctx).run();
        new TracingRules(ctx).run();
        new EntrypointRules(ctx).run();
        ctx.freezePlan();
        new RepeatedValueRules(ctx).run();
        ctx.freezePlan();
        new ExceptionRules(ctx).run();
        new LogCallRules(ctx).run();
        new LogArgumentRules(ctx).run();
        new MessageRules(ctx).run();
        dev.logast.orders.LogCoverage coverage = new CoverageRules(ctx).run();
        new DbRules(ctx).run();
        new FamilyRules(ctx).run();
        new RowLoopRules(ctx).run();
        new MdcRules(ctx).run();
        new AsyncRules(ctx).run();
        new AreaRules(ctx).run();
        new MetricRules(ctx).run();
        new EventMetricRules(ctx).run();
        MetricRules.tagKeyMismatch(ctx);
        if (ctx.enabled(METRIC_OUTCOME_UNMEASURED)) {
            outcomeGaps(ctx);
        }
        new ResilienceRules(ctx).run();
        new ErrorRules(ctx).run();
        new AuditRules(ctx).run();
        new VolumeRules(ctx).run();

        Set<String> ids = new HashSet<>();
        List<WorkOrder> orders = new ArrayList<>();
        for (OrderBuilder b : ctx.orders) {
            WorkOrder o = b.build();
            if (!ids.add(o.id())) {
                throw new IllegalStateException("duplicate order id " + o.id() + " for anchor " + b.anchor);
            }
            orders.add(o);
        }
        orders.removeIf(o -> !enabled(a.policy, o.rule()));
        orders.sort(Comparator.comparingInt((WorkOrder o) -> -rank(o.severity()))
                .thenComparing(WorkOrder::file).thenComparingInt(WorkOrder::line).thenComparing(WorkOrder::id));

        Map<String, String> fingerprints = new TreeMap<>();
        for (WorkOrder o : orders) {
            for (Edit e : o.edits()) {
                fingerprints.computeIfAbsent(e.file(), file -> fingerprint(a.project.root().resolve(file)));
            }
        }
        return new ScanResult(1, a.project.root().toString(), a.stats(), fingerprints, orders, coverage);
    }

    static String fingerprint(Path p) {
        try {
            return Files.exists(p) ? Hashing.sha256(Files.readString(p)) : "absent";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
