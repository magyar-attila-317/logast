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
    public static final String TRACE_KEY_HOLDS_APP_ID = "TRACE_KEY_HOLDS_APP_ID";
    public static final String MISSING_DOMAIN_MDC = "MISSING_DOMAIN_MDC";
    public static final String ENTRYPOINT_NOT_OBSERVED = "ENTRYPOINT_NOT_OBSERVED";
    public static final String ENTRYPOINT_SILENT = "ENTRYPOINT_SILENT";
    public static final String ASYNC_CONTEXT_LOSS = "ASYNC_CONTEXT_LOSS";
    public static final String REACTIVE_CONTEXT_LOSS = "REACTIVE_CONTEXT_LOSS";
    public static final String SETUP_OTEL_STARTER = "SETUP_OTEL_STARTER";
    public static final String SETUP_SUPPORT_STARTER = "SETUP_SUPPORT_STARTER";
    public static final String SETUP_LOGBACK_XML = "SETUP_LOGBACK_XML";
    public static final String SETUP_STRUCTURED_LOGGING = "SETUP_STRUCTURED_LOGGING";
    public static final String MESSAGE_PHRASING = "MESSAGE_PHRASING";
    public static final String MDC_KEY_ALIAS = "MDC_KEY_ALIAS";
    public static final String LOG_KEY_NESTING_CONFLICT = "LOG_KEY_NESTING_CONFLICT";
    public static final String LOG_ID_MISSING = "LOG_ID_MISSING";
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
    public static final String METRIC_CATALOG = "METRIC_CATALOG";
    public static final String MESSAGE_SUGGESTED = "MESSAGE_SUGGESTED";
    public static final String LOG_OBJECT_ARGUMENT = "LOG_OBJECT_ARGUMENT";
    public static final String LOG_COLLECTION_ARGUMENT = "LOG_COLLECTION_ARGUMENT";
    public static final String LOG_HELPER = "LOG_HELPER";
    public static final String MESSAGE_VAGUE = "MESSAGE_VAGUE";
    public static final String BUSINESS_EVENT_TIMESTAMP = "BUSINESS_EVENT_TIMESTAMP";
    public static final String LOG_KEY_VALUE_IN_MDC = "LOG_KEY_VALUE_IN_MDC";
    public static final String AREA_MDC_MISSING = "AREA_MDC_MISSING";
    public static final String LOG_LEVEL = "LOG_LEVEL";
    public static final String EXCEPTION_LOGGED_TWICE = "EXCEPTION_LOGGED_TWICE";
    public static final String HTTP_CLIENT_UNTRACED = "HTTP_CLIENT_UNTRACED";
    public static final String TRACING_SAMPLING = "TRACING_SAMPLING";
    public static final String SCHEDULED_TASK_UNTRACED = "SCHEDULED_TASK_UNTRACED";
    public static final String KAFKA_OBSERVATION_DISABLED = "KAFKA_OBSERVATION_DISABLED";
    public static final String RABBIT_OBSERVATION_DISABLED = "RABBIT_OBSERVATION_DISABLED";
    public static final String JOB_LAUNCH_UNTRACED = "JOB_LAUNCH_UNTRACED";
    public static final String OBSERVED_ANNOTATION_INACTIVE = "OBSERVED_ANNOTATION_INACTIVE";
    public static final String SPAN_NOT_CLOSED = "SPAN_NOT_CLOSED";
    public static final String TRACING_EXPORT_UNCONFIGURED = "TRACING_EXPORT_UNCONFIGURED";
    public static final String SPAN_NAME_DYNAMIC = "SPAN_NAME_DYNAMIC";
    public static final String TRACE_ID_NOT_LOGGED = "TRACE_ID_NOT_LOGGED";
    public static final String SSE_BROADCAST_CONTEXT = "SSE_BROADCAST_CONTEXT";
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
    public static final String DB_NO_TIMEOUT = "DB_NO_TIMEOUT";
    public static final String REMOTE_CALL_IN_TRANSACTION = "REMOTE_CALL_IN_TRANSACTION";
    public static final String SCHEDULED_WITHOUT_LOCK = "SCHEDULED_WITHOUT_LOCK";
    public static final String RETRY_INACTIVE = "RETRY_INACTIVE";
    public static final String SCHEDULER_SINGLE_THREAD = "SCHEDULER_SINGLE_THREAD";
    public static final String SHUTDOWN_NOT_GRACEFUL = "SHUTDOWN_NOT_GRACEFUL";
    public static final String HEALTH_PROBE_MISCONFIGURED = "HEALTH_PROBE_MISCONFIGURED";
    public static final String READINESS_WITHOUT_DATABASE = "READINESS_WITHOUT_DATABASE";
    public static final String CACHE_UNBOUNDED = "CACHE_UNBOUNDED";
    public static final String RABBIT_NO_ERROR_HANDLER = "RABBIT_NO_ERROR_HANDLER";
    public static final String RETRY_IN_TRANSACTION = "RETRY_IN_TRANSACTION";
    public static final String RETRY_SETTINGS_HARDCODED = "RETRY_SETTINGS_HARDCODED";
    public static final String RETRY_RECOMMENDED = "RETRY_RECOMMENDED";
    public static final String NESTED_RETRY = "NESTED_RETRY";
    public static final String RETRY_BYPASSED = "RETRY_BYPASSED";
    // errors
    public static final String ERROR_DETAILS_EXPOSED = "ERROR_DETAILS_EXPOSED";
    public static final String ERROR_RESPONSE_UNTRACED = "ERROR_RESPONSE_UNTRACED";
    public static final String ASYNC_ERROR_LOST = "ASYNC_ERROR_LOST";
    public static final String CAUSE_DROPPED = "CAUSE_DROPPED";
    public static final String CATCH_ALL_DEFAULT = "CATCH_ALL_DEFAULT";
    public static final String AUDIT_FAILURES_UNRECORDED = "AUDIT_FAILURES_UNRECORDED";
    public static final String AUDIT_RECORD_INCOMPLETE = "AUDIT_RECORD_INCOMPLETE";
    public static final String AUDIT_LOG_MIXED = "AUDIT_LOG_MIXED";
    public static final String DATA_CHANGES_UNAUDITED = "DATA_CHANGES_UNAUDITED";
    public static final String UNMAPPED_EXCEPTION = "UNMAPPED_EXCEPTION";
    public static final String CLIENT_ERROR_AS_SERVER_ERROR = "CLIENT_ERROR_AS_SERVER_ERROR";
    public static final String PROBLEM_DETAILS_DISABLED = "PROBLEM_DETAILS_DISABLED";
    public static final String UNEXPECTED_ERROR_UNLOGGED = "UNEXPECTED_ERROR_UNLOGGED";
    public static final String ERROR_HANDLER_STATUS_OK = "ERROR_HANDLER_STATUS_OK";
    public static final String TX_COMMITS_ON_CHECKED = "TX_COMMITS_ON_CHECKED";
    public static final String REQUEST_NOT_VALIDATED = "REQUEST_NOT_VALIDATED";
    // audit
    public static final String AUDIT_MISSING = "AUDIT_MISSING";
    public static final String AUDIT_SECURITY_EVENTS = "AUDIT_SECURITY_EVENTS";
    public static final String AUDIT_LOGGER_UNROUTED = "AUDIT_LOGGER_UNROUTED";
    public static final String AUDIT_BEFORE_COMMIT = "AUDIT_BEFORE_COMMIT";
    public static final String AUDIT_RECORD_SECRET = "AUDIT_RECORD_SECRET";
    public static final String AUDIT_ACTOR_LOST_ASYNC = "AUDIT_ACTOR_LOST_ASYNC";

    /**
     * Rules whose AUTO fixes use logast's support classes ({@link RuleContext#dependOnStarter} checks the list).
     * SETUP_SUPPORT_STARTER belongs to each of their packs.
     */
    static final Set<String> SUPPORT_USERS = Set.of(ENTRYPOINT_NOT_OBSERVED, MISSING_DOMAIN_MDC, ENTRYPOINT_SILENT,
            ASYNC_CONTEXT_LOSS, SCHEDULED_TASK_UNTRACED, REPEATED_LOG_VALUE, MDC_NOT_CLOSEABLE, MDC_CLEAR, MDC_NESTED_KEY,
            MDC_KEY_ALIAS, JOB_OUTCOME_MISSING, DB_OUTCOME_UNLOGGED, BUSINESS_EVENT_UNMEASURED, METRIC_OUTCOME_UNMEASURED,
            JOB_LAUNCH_UNTRACED, ERROR_RESPONSE_UNTRACED);

    /**
     * The support class a rule's fix needs alone, for the rules whose fixes need one class and not the core set
     * (ObservedContext, MdcScope, …). With only such rules selected, vendor mode copies just that class (the rule does,
     * see ErrorRules) and dependency mode adds the starter only when the fix applies.
     */
    static final Map<String, String> SUPPORT_SINGLE_CLASS = Map.of(ERROR_RESPONSE_UNTRACED, "ProblemTraceId");

    /** Rule → packs it belongs to. A rule runs when any of its packs is selected (policy.packs / --pack). */
    private static final Map<String, Set<String>> PACKS = new HashMap<>();

    static {
        for (String r : List.of(SWALLOWED_EXCEPTION, LOST_THROWABLE, LOG_AND_RETHROW, EXCEPTION_LOGGED_TWICE,
                STRING_CONCAT_LOG, LOG_FORMAT, GLOSSARY_VIOLATION, PII_IN_LOG, MDC_NOT_CLOSEABLE, MDC_KEY_ALIAS,
                MISSING_DOMAIN_MDC, AREA_MDC_MISSING, ENTRYPOINT_SILENT, MESSAGE_PHRASING, LOG_KEY_STYLE, LOG_LEVEL, REPEATED_LOG_VALUE,
                MDC_CLEAR, MDC_NESTED_KEY, MDC_NOT_OWNER, JOB_OUTCOME_MISSING, SKIP_UNLOGGED, STATE_CHANGE_UNLOGGED,
                BATCH_SUMMARY_MISSING, DB_OUTCOME_UNLOGGED, MESSAGE_SUGGESTED, LIFECYCLE_UNEVEN, LOG_OBJECT_ARGUMENT, LOG_COLLECTION_ARGUMENT, LOG_HELPER, MESSAGE_VAGUE, BUSINESS_EVENT_TIMESTAMP, LOG_KEY_VALUE_IN_MDC, LOG_KEY_NESTING_CONFLICT, LOG_ID_MISSING,
                SETUP_LOGBACK_XML, SETUP_STRUCTURED_LOGGING, RUNTIME_MDC_LOST, RUNTIME_NULL_VALUE, LOG_IN_LOOP, LOG_VOLUME)) {
            PACKS.put(r, Set.of("logging"));
        }
        for (String r : List.of(ENTRYPOINT_NOT_OBSERVED, MANUAL_TRACE_MDC, TRACE_KEY_HOLDS_APP_ID, SETUP_OTEL_STARTER, HTTP_CLIENT_UNTRACED,
                KAFKA_OBSERVATION_DISABLED, RABBIT_OBSERVATION_DISABLED, JOB_LAUNCH_UNTRACED, RUNTIME_TRACE_LOST, TRACING_SAMPLING,
                SPAN_NOT_CLOSED, TRACING_EXPORT_UNCONFIGURED, SPAN_NAME_DYNAMIC, TRACE_ID_NOT_LOGGED)) {
            PACKS.put(r, Set.of("tracing"));
        }
        // one switch for @Observed / @NewSpan (tracing) and @Observed / @Timed / @Counted (metrics): each pack alone turns it on
        PACKS.put(OBSERVED_ANNOTATION_INACTIVE, Set.of("tracing", "metrics"));
        // context propagation matters to MDC (logging) and spans (tracing); the starter serves every pack
        PACKS.put(ASYNC_CONTEXT_LOSS, Set.of("logging", "tracing"));
        PACKS.put(SCHEDULED_TASK_UNTRACED, Set.of("logging", "tracing"));
        // a broadcast's per-subscriber lines: the subscriber is missing from MDC (logging) and the line sits in the
        // sender's trace, not the subscriber's (tracing)
        PACKS.put(SSE_BROADCAST_CONTEXT, Set.of("logging", "tracing"));
        // one switch makes the trace (tracing) and the trace ids in log lines (logging) follow a Reactor pipeline (#14)
        PACKS.put(REACTIVE_CONTEXT_LOSS, Set.of("logging", "tracing"));
        for (String r : List.of(METRIC_NAMING, METRIC_HIGH_CARDINALITY_TAG, METRIC_DIRECT_REGISTRY, METRIC_TAG_KEY,
                METRIC_TAG_VALUE, METRIC_REGISTERED_PER_CALL, METRIC_GAUGE_WEAK_REF, BUSINESS_EVENT_UNMEASURED, METRIC_TAG_KEYS_MISMATCH)) {
            PACKS.put(r, Set.of("metrics"));
        }
        for (String r : List.of(HTTP_CLIENT_NO_TIMEOUT, KAFKA_NO_ERROR_HANDLER, UNBOUNDED_EXECUTOR, BLOCKING_WAIT_UNBOUNDED, ROW_BY_ROW_WRITE,
                DB_NO_TIMEOUT, REMOTE_CALL_IN_TRANSACTION, SCHEDULED_WITHOUT_LOCK, RETRY_INACTIVE, SCHEDULER_SINGLE_THREAD,
                SHUTDOWN_NOT_GRACEFUL, HEALTH_PROBE_MISCONFIGURED, READINESS_WITHOUT_DATABASE, CACHE_UNBOUNDED,
                RABBIT_NO_ERROR_HANDLER, RETRY_IN_TRANSACTION, RETRY_SETTINGS_HARDCODED, RETRY_RECOMMENDED, NESTED_RETRY, RETRY_BYPASSED)) {
            PACKS.put(r, Set.of("resilience"));
        }
        for (String r : List.of(ERROR_DETAILS_EXPOSED, UNMAPPED_EXCEPTION, CLIENT_ERROR_AS_SERVER_ERROR, PROBLEM_DETAILS_DISABLED,
                ERROR_RESPONSE_UNTRACED, ASYNC_ERROR_LOST, CAUSE_DROPPED, CATCH_ALL_DEFAULT, UNEXPECTED_ERROR_UNLOGGED,
                ERROR_HANDLER_STATUS_OK, TX_COMMITS_ON_CHECKED, REQUEST_NOT_VALIDATED)) {
            PACKS.put(r, Set.of("errors"));
        }
        for (String r : List.of(AUDIT_MISSING, AUDIT_SECURITY_EVENTS, AUDIT_FAILURES_UNRECORDED, AUDIT_RECORD_INCOMPLETE, AUDIT_LOG_MIXED, DATA_CHANGES_UNAUDITED,
                AUDIT_LOGGER_UNROUTED, AUDIT_BEFORE_COMMIT, AUDIT_RECORD_SECRET, AUDIT_ACTOR_LOST_ASYNC)) {
            PACKS.put(r, Set.of("audit"));
        }
        // service name/version on logs, meters and spans: the packs that emit telemetry
        PACKS.put(SETUP_SERVICE_IDENTITY, Set.of("logging", "tracing", "metrics"));
        PACKS.put(METRIC_OUTCOME_UNMEASURED, Set.of("metrics"));
        PACKS.put(METRIC_CATALOG, Set.of("metrics"));
        // the support set-up belongs to every pack whose fixes use it: a pack selected on its own brings it along (#87)
        Set<String> supportPacks = new TreeSet<>();
        SUPPORT_USERS.forEach(r -> supportPacks.addAll(PACKS.get(r)));
        PACKS.put(SETUP_SUPPORT_STARTER, Set.copyOf(supportPacks));
    }

    /** A selected pack's fixes use the core support set (more than the single classes of SUPPORT_SINGLE_CLASS). */
    static boolean coreSupportSelected(Policy policy) {
        return SUPPORT_USERS.stream().filter(r -> !SUPPORT_SINGLE_CLASS.containsKey(r))
                .anyMatch(r -> packsOf(r).stream().anyMatch(policy::packSelected));
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
                SETUP_SERVICE_IDENTITY, TRACING_SAMPLING, KAFKA_OBSERVATION_DISABLED, RABBIT_OBSERVATION_DISABLED, ERROR_RESPONSE_UNTRACED, DB_NO_TIMEOUT, HTTP_CLIENT_UNTRACED, HTTP_CLIENT_NO_TIMEOUT,
                JOB_LAUNCH_UNTRACED, RETRY_INACTIVE, OBSERVED_ANNOTATION_INACTIVE, REACTIVE_CONTEXT_LOSS,
                // a retry answered "yes" in retry.yaml (#109) is added before RETRY_INACTIVE enables it (same step, next
                // round) and RETRY_SETTINGS_HARDCODED (structure) moves its values: one pass
                RETRY_RECOMMENDED,
                // #269: the key is renamed before MDC_NOT_CLOSEABLE scopes it (next step, after a rescan)
                TRACE_KEY_HOLDS_APP_ID)) {
            STEPS.put(r, Step.SETUP);
        }
        for (String r : List.of(MDC_NOT_CLOSEABLE, MDC_CLEAR, MDC_NOT_OWNER, ENTRYPOINT_NOT_OBSERVED, SCHEDULED_TASK_UNTRACED,
                ASYNC_CONTEXT_LOSS, AUDIT_LOG_MIXED, RETRY_SETTINGS_HARDCODED)) {
            STEPS.put(r, Step.STRUCTURE);
        }
        // BUSINESS_EVENT_UNMEASURED reads the lifecycle lines: it goes with the step that adds them (a job outcome
        // line can be what makes a method a business event), not before it
        for (String r : List.of(ENTRYPOINT_SILENT, JOB_OUTCOME_MISSING, BATCH_SUMMARY_MISSING, SKIP_UNLOGGED, STATE_CHANGE_UNLOGGED,
                DB_OUTCOME_UNLOGGED, LIFECYCLE_UNEVEN, SWALLOWED_EXCEPTION, LOST_THROWABLE, BUSINESS_EVENT_UNMEASURED,
                ASYNC_ERROR_LOST, UNEXPECTED_ERROR_UNLOGGED)) {
            STEPS.put(r, Step.LINES);
        }
        // LOG_ID_MISSING (#120) with them: an id it adds to several lines of a method is then REPEATED_LOG_VALUE's to move
        // into MDC, in the same step's rounds (one pass)
        for (String r : List.of(MISSING_DOMAIN_MDC, REPEATED_LOG_VALUE, MDC_KEY_ALIAS, MDC_NESTED_KEY, AREA_MDC_MISSING, LOG_ID_MISSING)) {
            STEPS.put(r, Step.MDC);
        }
        for (String r : List.of(LOG_FORMAT, STRING_CONCAT_LOG, LOG_KEY_STYLE, GLOSSARY_VIOLATION, MESSAGE_PHRASING, PII_IN_LOG,
                LOG_OBJECT_ARGUMENT, LOG_COLLECTION_ARGUMENT, LOG_KEY_VALUE_IN_MDC, MESSAGE_SUGGESTED, MESSAGE_VAGUE, BUSINESS_EVENT_TIMESTAMP, LOG_HELPER)) {
            STEPS.put(r, Step.MESSAGES);
        }
        for (String r : List.of(LOG_AND_RETHROW, EXCEPTION_LOGGED_TWICE, CAUSE_DROPPED)) {
            STEPS.put(r, Step.EXCEPTIONS);
        }
        for (String r : List.of(LOG_LEVEL, LOG_IN_LOOP, LOG_VOLUME)) {
            STEPS.put(r, Step.LEVELS);
        }
        for (String r : List.of(METRIC_NAMING, METRIC_HIGH_CARDINALITY_TAG, METRIC_DIRECT_REGISTRY, METRIC_TAG_KEY, METRIC_TAG_VALUE,
                METRIC_REGISTERED_PER_CALL, METRIC_GAUGE_WEAK_REF, METRIC_TAG_KEYS_MISMATCH, METRIC_OUTCOME_UNMEASURED, METRIC_CATALOG,
                UNBOUNDED_EXECUTOR, KAFKA_NO_ERROR_HANDLER, BLOCKING_WAIT_UNBOUNDED, ROW_BY_ROW_WRITE, REMOTE_CALL_IN_TRANSACTION, SCHEDULED_WITHOUT_LOCK, SCHEDULER_SINGLE_THREAD, SHUTDOWN_NOT_GRACEFUL, HEALTH_PROBE_MISCONFIGURED, READINESS_WITHOUT_DATABASE, CACHE_UNBOUNDED, RABBIT_NO_ERROR_HANDLER, RETRY_IN_TRANSACTION, NESTED_RETRY, RETRY_BYPASSED, CATCH_ALL_DEFAULT, AUDIT_FAILURES_UNRECORDED, AUDIT_RECORD_INCOMPLETE, DATA_CHANGES_UNAUDITED, ERROR_DETAILS_EXPOSED,
                UNMAPPED_EXCEPTION, CLIENT_ERROR_AS_SERVER_ERROR, PROBLEM_DETAILS_DISABLED, ERROR_HANDLER_STATUS_OK, TX_COMMITS_ON_CHECKED, REQUEST_NOT_VALIDATED, AUDIT_MISSING, AUDIT_SECURITY_EVENTS,
                AUDIT_LOGGER_UNROUTED, AUDIT_BEFORE_COMMIT, AUDIT_RECORD_SECRET, AUDIT_ACTOR_LOST_ASYNC,
                MANUAL_TRACE_MDC, LOG_KEY_NESTING_CONFLICT, SPAN_NOT_CLOSED, TRACING_EXPORT_UNCONFIGURED, SPAN_NAME_DYNAMIC, TRACE_ID_NOT_LOGGED, SSE_BROADCAST_CONTEXT, RUNTIME_TRACE_LOST, RUNTIME_MDC_LOST, RUNTIME_NULL_VALUE)) {
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

    /** Every pack some rule belongs to: what {@code packs:} and {@code --pack} can select. */
    public static Set<String> packs() {
        Set<String> out = new TreeSet<>();
        PACKS.values().forEach(out::addAll);
        return out;
    }

    public static Set<String> packsOf(String rule) {
        return PACKS.getOrDefault(rule, Set.of("logging"));
    }

    /** Enabled in the policy and one of its packs is selected. */
    public static boolean enabled(Policy policy, String rule) {
        return policy.ruleEnabled(rule) && packsOf(rule).stream().anyMatch(policy::packSelected);
    }

    /**
     * A scope of {@code op}'s key in {@code m} sets provably the value the caller already scoped, so MDC_NESTED_KEY
     * unwraps it; a derived value is only reported (#79).
     */
    public static boolean nestedScopeRepeatsCallerValue(dev.logast.Analysis a, dev.logast.model.MethodInfo m, dev.logast.model.MdcOp op) {
        return op.key() != null && MdcRules.valueOf(op) != null && MdcRules.repeatsCallerValue(a, m, op.key(), MdcRules.valueOf(op));
    }

    private static final Map<String, String> SEVERITY = Map.ofEntries(
            Map.entry(SWALLOWED_EXCEPTION, "high"), Map.entry(PII_IN_LOG, "high"), Map.entry(ASYNC_CONTEXT_LOSS, "high"),
            Map.entry(REACTIVE_CONTEXT_LOSS, "high"),
            Map.entry(SETUP_SUPPORT_STARTER, "high"), Map.entry(SETUP_OTEL_STARTER, "high"),
            Map.entry(ENTRYPOINT_NOT_OBSERVED, "high"),
            Map.entry(LOST_THROWABLE, "medium"), Map.entry(MISSING_DOMAIN_MDC, "medium"),
            Map.entry(ENTRYPOINT_SILENT, "medium"), Map.entry(MANUAL_TRACE_MDC, "medium"), Map.entry(TRACE_KEY_HOLDS_APP_ID, "high"),
            Map.entry(SETUP_LOGBACK_XML, "medium"), Map.entry(LOG_AND_RETHROW, "medium"),
            Map.entry(SETUP_STRUCTURED_LOGGING, "medium"), Map.entry(MDC_NOT_CLOSEABLE, "low"),
            Map.entry(STRING_CONCAT_LOG, "low"), Map.entry(LOG_FORMAT, "low"), Map.entry(GLOSSARY_VIOLATION, "low"),
            Map.entry(MESSAGE_PHRASING, "low"), Map.entry(MDC_KEY_ALIAS, "medium"), Map.entry(LOG_KEY_NESTING_CONFLICT, "medium"), Map.entry(LOG_ID_MISSING, "low"), Map.entry(LOG_KEY_STYLE, "low"), Map.entry(REPEATED_LOG_VALUE, "medium"),
            Map.entry(MDC_CLEAR, "high"), Map.entry(MDC_NOT_OWNER, "high"),
            Map.entry(JOB_OUTCOME_MISSING, "medium"), Map.entry(SKIP_UNLOGGED, "low"), Map.entry(STATE_CHANGE_UNLOGGED, "medium"),
            Map.entry(BATCH_SUMMARY_MISSING, "low"), Map.entry(DB_OUTCOME_UNLOGGED, "medium"),
            Map.entry(SETUP_SERVICE_IDENTITY, "medium"), Map.entry(LIFECYCLE_UNEVEN, "low"), Map.entry(ROW_BY_ROW_WRITE, "medium"), Map.entry(METRIC_OUTCOME_UNMEASURED, "low"), Map.entry(METRIC_CATALOG, "low"), Map.entry(MESSAGE_SUGGESTED, "low"), Map.entry(MDC_NESTED_KEY, "medium"), Map.entry(LOG_OBJECT_ARGUMENT, "high"),
            Map.entry(LOG_COLLECTION_ARGUMENT, "medium"), Map.entry(LOG_HELPER, "medium"), Map.entry(MESSAGE_VAGUE, "low"),
            Map.entry(BUSINESS_EVENT_TIMESTAMP, "low"),
            Map.entry(LOG_KEY_VALUE_IN_MDC, "low"),
            Map.entry(AREA_MDC_MISSING, "medium"), Map.entry(LOG_LEVEL, "medium"), Map.entry(EXCEPTION_LOGGED_TWICE, "medium"),
            Map.entry(HTTP_CLIENT_UNTRACED, "high"), Map.entry(TRACING_SAMPLING, "medium"),
            Map.entry(METRIC_TAG_KEY, "low"), Map.entry(METRIC_TAG_VALUE, "low"), Map.entry(METRIC_REGISTERED_PER_CALL, "low"),
            Map.entry(METRIC_GAUGE_WEAK_REF, "high"), Map.entry(BUSINESS_EVENT_UNMEASURED, "medium"),
            Map.entry(METRIC_TAG_KEYS_MISMATCH, "high"),
            Map.entry(HTTP_CLIENT_NO_TIMEOUT, "high"), Map.entry(KAFKA_NO_ERROR_HANDLER, "medium"),
            Map.entry(UNBOUNDED_EXECUTOR, "medium"), Map.entry(BLOCKING_WAIT_UNBOUNDED, "medium"),
            Map.entry(ERROR_DETAILS_EXPOSED, "high"), Map.entry(ERROR_RESPONSE_UNTRACED, "medium"), Map.entry(DB_NO_TIMEOUT, "high"), Map.entry(REMOTE_CALL_IN_TRANSACTION, "medium"), Map.entry(SCHEDULED_WITHOUT_LOCK, "medium"), Map.entry(RETRY_INACTIVE, "high"), Map.entry(SCHEDULER_SINGLE_THREAD, "medium"), Map.entry(SHUTDOWN_NOT_GRACEFUL, "medium"), Map.entry(HEALTH_PROBE_MISCONFIGURED, "medium"), Map.entry(READINESS_WITHOUT_DATABASE, "low"), Map.entry(CACHE_UNBOUNDED, "medium"), Map.entry(RABBIT_NO_ERROR_HANDLER, "medium"), Map.entry(RETRY_IN_TRANSACTION, "medium"), Map.entry(RETRY_SETTINGS_HARDCODED, "low"), Map.entry(RETRY_RECOMMENDED, "low"), Map.entry(NESTED_RETRY, "medium"), Map.entry(RETRY_BYPASSED, "high"), Map.entry(ASYNC_ERROR_LOST, "high"), Map.entry(CAUSE_DROPPED, "medium"), Map.entry(CATCH_ALL_DEFAULT, "medium"), Map.entry(AUDIT_FAILURES_UNRECORDED, "medium"), Map.entry(AUDIT_RECORD_INCOMPLETE, "medium"), Map.entry(AUDIT_LOG_MIXED, "low"), Map.entry(DATA_CHANGES_UNAUDITED, "low"), Map.entry(UNMAPPED_EXCEPTION, "medium"),
            Map.entry(CLIENT_ERROR_AS_SERVER_ERROR, "medium"), Map.entry(PROBLEM_DETAILS_DISABLED, "low"),
            Map.entry(UNEXPECTED_ERROR_UNLOGGED, "high"), Map.entry(ERROR_HANDLER_STATUS_OK, "medium"), Map.entry(TX_COMMITS_ON_CHECKED, "medium"),
            Map.entry(REQUEST_NOT_VALIDATED, "medium"),
            Map.entry(AUDIT_MISSING, "medium"), Map.entry(AUDIT_SECURITY_EVENTS, "medium"), Map.entry(SCHEDULED_TASK_UNTRACED, "medium"), Map.entry(KAFKA_OBSERVATION_DISABLED, "high"), Map.entry(RABBIT_OBSERVATION_DISABLED, "high"), Map.entry(JOB_LAUNCH_UNTRACED, "high"),
            Map.entry(OBSERVED_ANNOTATION_INACTIVE, "high"), Map.entry(SPAN_NOT_CLOSED, "medium"), Map.entry(TRACING_EXPORT_UNCONFIGURED, "medium"),
            Map.entry(AUDIT_LOGGER_UNROUTED, "medium"), Map.entry(AUDIT_BEFORE_COMMIT, "medium"), Map.entry(AUDIT_RECORD_SECRET, "high"),
            Map.entry(AUDIT_ACTOR_LOST_ASYNC, "high"),
            Map.entry(SPAN_NAME_DYNAMIC, "high"), Map.entry(TRACE_ID_NOT_LOGGED, "medium"), Map.entry(SSE_BROADCAST_CONTEXT, "low"),
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
        Map<String, Integer> seen = new HashMap<>();
        List<OrderBuilder> builders = new ArrayList<>();
        List<MetricsMap.Gap> gaps = MetricsMap.of(ctx.a, ctx.entry.observedAnnotationsActive()).gaps();
        List<dev.logast.apply.MetricsFile.Spot> spots = new ArrayList<>();
        for (MetricsMap.Gap g : gaps) {
            MetricsMap.Unmeasured u = g.outcome();
            // not the message text: a reworded line (messages.yaml, a key moved to MDC) keeps its id and its decision
            String anchor = u.file() + "|" + u.method() + "|" + u.event();
            int n = seen.merge(anchor, 1, Integer::sum);   // the same event twice in one method: number the repeats
            OrderBuilder b = ctx.order(METRIC_OUTCOME_UNMEASURED, n == 1 ? anchor : anchor + "|" + n).at(u.file(), u.line())
                    .title("\"" + u.message() + "\" is logged but not counted (outcome=" + u.event() + ", frequency " + u.frequency() + ")")
                    .data("name", g.name()).data("outcome", u.event()).data("event", u.event())
                    .data("message", u.level().toUpperCase(java.util.Locale.ROOT) + " \"" + u.message() + "\"")
                    .data("frequency", u.frequency()).data("paths", u.paths()).data("tagAvailable", g.tagAvailable())
                    .doneWhen("a metric counts this outcome, or its decision in " + dev.logast.apply.MetricsFile.PATH
                            + " is applied (logast apply .)");
            if (!ctx.policy.metrics().amounts().equals("off") && !g.amounts().isEmpty()) {
                b.data("amounts", List.copyOf(g.amounts().keySet()));   // numbers the line logs: metrics.yaml offers them
            }
            b.method = u.method();
            builders.add(b);
            spots.add(new dev.logast.apply.MetricsFile.Spot(b.id(), u.file(), b.method == null ? "" : b.method, u.event()));
        }
        var decisions = dev.logast.apply.MetricsFile.match(dev.logast.apply.MetricsFile.read(ctx.a.project.root()), spots);
        Map<String, String> amountMeters = new HashMap<>();   // name.amount → total | per-run: one meter type per name
        for (int i = 0; i < gaps.size(); i++) {
            MetricsMap.Gap g = gaps.get(i);
            MetricsMap.Unmeasured u = g.outcome();
            OrderBuilder b = builders.get(i);
            String advice = "Decide in " + dev.logast.apply.MetricsFile.PATH + ": count: counter (Outcomes.count(\"" + g.name()
                    + "\", \"" + u.event() + "\"))" + (g.tagAvailable() ? ", tag (Outcomes.tag(\"" + u.event() + "\") on the running timer)" : "")
                    + " or skip; then logast apply . Count it only if a dashboard or alert needs it.";
            dev.logast.apply.MetricsFile.Decision d = decisions.get(b.id());
            String marker = null;
            String category = d == null ? defaultCategory(ctx, g) : null;
            if (category != null) {
                // counted by default (metrics.defaults), best effort: marked for review, with how sure the name is
                d = new dev.logast.apply.MetricsFile.Decision("counter", "", "", u.file(), u.method(), u.event(),
                        category.equals("db-writes") && !ctx.policy.metrics().amounts().equals("off") ? "rows" : "", "");
                marker = "// logast:metric review, confidence: " + confidence(ctx, g);
            }
            String why = decide(ctx, b, g, d, amountMeters, marker);
            if (why != null && category == null && d != null) {
                b.data("problem", why);   // shown next to the decision in metrics.yaml
            }
            if (category != null && why == null) {
                b.data("auto", true);   // decided by default: not an entry to decide in metrics.yaml
            } else if (category != null) {
                b.clearEdits();   // could not be written by default: it waits in metrics.yaml like any other
            }
            b.suggestion(why != null ? why + " " + advice : advice);
        }
    }

    /** Turns a metrics.yaml decision into edits; returns a problem to report, or null. */
    /** The log call sits in a lambda inside {@code stmt}, under an if / switch / ternary / catch within that lambda. */
    static boolean conditionalInLambda(org.openrewrite.java.tree.J stmt, org.openrewrite.java.tree.J.MethodInvocation call) {
        boolean[] found = {false};
        new org.openrewrite.java.JavaIsoVisitor<Integer>() {
            int lambdas;
            int conditions;

            @Override
            public org.openrewrite.java.tree.J.Lambda visitLambda(org.openrewrite.java.tree.J.Lambda l, Integer p) {
                lambdas++;
                int outer = conditions;
                conditions = 0;
                try {
                    return super.visitLambda(l, p);
                } finally {
                    conditions = outer;
                    lambdas--;
                }
            }

            @Override
            public org.openrewrite.java.tree.J.If visitIf(org.openrewrite.java.tree.J.If i, Integer p) {
                conditions++;
                try {
                    return super.visitIf(i, p);
                } finally {
                    conditions--;
                }
            }

            @Override
            public org.openrewrite.java.tree.J.Ternary visitTernary(org.openrewrite.java.tree.J.Ternary t, Integer p) {
                conditions++;
                try {
                    return super.visitTernary(t, p);
                } finally {
                    conditions--;
                }
            }

            @Override
            public org.openrewrite.java.tree.J.Case visitCase(org.openrewrite.java.tree.J.Case c, Integer p) {
                conditions++;
                try {
                    return super.visitCase(c, p);
                } finally {
                    conditions--;
                }
            }

            @Override
            public org.openrewrite.java.tree.J.Try.Catch visitCatch(org.openrewrite.java.tree.J.Try.Catch c, Integer p) {
                conditions++;
                try {
                    return super.visitCatch(c, p);
                } finally {
                    conditions--;
                }
            }

            @Override
            public org.openrewrite.java.tree.J.MethodInvocation visitMethodInvocation(org.openrewrite.java.tree.J.MethodInvocation mi, Integer p) {
                if (mi == call || mi.getId().equals(call.getId())) {
                    found[0] = lambdas > 0 && conditions > 0;
                }
                return super.visitMethodInvocation(mi, p);
            }
        }.visit(stmt, 0);
        return found[0];
    }

    /** The metrics.defaults category a logged outcome falls into, or null. */
    private static String defaultCategory(RuleContext ctx, MetricsMap.Gap g) {
        List<String> on = ctx.policy.metrics().defaults();
        String event = g.outcome().event();
        if (on.contains("db-writes") && g.amounts().containsKey("rows") && Set.of("inserted", "updated", "deleted", "saved").contains(event)) {
            return "db-writes";
        }
        if (on.contains("failures") && event.equals("failed")) {
            return "failures";
        }
        if (on.contains("skips") && event.equals("skipped")) {
            return "skips";
        }
        if (on.contains("jobs") && Set.of("completed", "failed", "skipped").contains(event) && jobFlow(ctx).contains(g.method().key())) {
            return "jobs";
        }
        return null;
    }

    /**
     * Methods that are a job's or listener's own flow: the entrypoint and what it calls up to
     * {@link EntrypointRules#OWN_FLOW_DEPTH} levels down (an executor's abstract step lands in each job's doExecute).
     */
    private static Set<String> jobFlow(RuleContext ctx) {
        if (ctx.jobFlow == null) {
            Set<String> out = new HashSet<>();
            Deque<Map.Entry<dev.logast.model.MethodInfo, Integer>> todo = new ArrayDeque<>();
            ctx.a.entrypoints.stream().filter(ep -> ep.kind().is(dev.logast.model.Entrypoint.Trait.ONE_OUTCOME)).forEach(ep -> todo.add(Map.entry(ep.method(), 0)));
            while (!todo.isEmpty()) {
                var cur = todo.poll();
                if (!out.add(cur.getKey().key()) || cur.getValue() >= EntrypointRules.OWN_FLOW_DEPTH) {
                    continue;
                }
                ctx.a.graph.out(cur.getKey()).forEach(e -> todo.add(Map.entry(e.to(), cur.getValue() + 1)));
            }
            ctx.jobFlow = out;
        }
        return ctx.jobFlow;
    }


    /**
     * How sure the metric name is: high when every word of it is a vocabulary term, low when the log line's wording was
     * generated by logast (the name then comes from class or method names), medium otherwise.
     */
    private static String confidence(RuleContext ctx, MetricsMap.Gap g) {
        String line = g.method().file().lineText(dev.logast.parse.Pos.line(g.log().call()));
        if (line != null && line.contains(DbRules.WORDING_MARKER)) {
            return "low";
        }
        Set<String> terms = new HashSet<>();
        ctx.policy.terms().forEach(t -> terms.add(t.toLowerCase(java.util.Locale.ROOT)));
        boolean known = Arrays.stream(g.name().split("\\.")).allMatch(w -> terms.contains(w)
                || w.endsWith("s") && terms.contains(w.substring(0, w.length() - 1)));
        return known ? "high" : "medium";
    }

    private static String decide(RuleContext ctx, OrderBuilder b, MetricsMap.Gap g, dev.logast.apply.MetricsFile.Decision d,
                                 Map<String, String> amountMeters, String marker) {
        if (d == null) {
            return null;
        }
        dev.logast.parse.SourceFileInfo f = g.method().file();
        org.openrewrite.java.tree.J stmt = CoverageRules.enclosingStatement(g.method(), g.log().call());
        if (stmt == null) {
            return "Cannot place a line next to this log call automatically (not a plain statement).";
        }
        if (g.log().offThread()) {
            // the lambda runs later, on another thread: a line after the statement counts the hand-off, not the outcome
            return "The log line runs in a lambda on another thread, later: a counting line after the statement would count "
                    + "when the work is handed off, not when it happens; count it inside the lambda, or mark it skip.";
        }
        if (conditionalInLambda(stmt, g.log().call())) {
            // a line after the statement would count every run, not the failures the lambda logs (whenComplete, doOnError)
            return "The log line runs inside a lambda, only under a condition: a counting line after the statement would "
                    + "count every run; count it inside the lambda, next to the log line, or mark it skip.";
        }
        if (stmt instanceof org.openrewrite.java.tree.J.Return || stmt instanceof org.openrewrite.java.tree.J.Throw) {
            // a line after it would never run (the log call sits in a lambda of the returned pipeline, e.g. doOnError)
            return "The log line is part of a return/throw statement: a counting line after it would never run; count it "
                    + "where it happens (inside the lambda) or mark it skip.";
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
                    return "metrics.yaml: name and outcome must be lower case words joined by dots (got \"" + name + "\", \"" + outcome + "\").";
                }
                if (d.count().equals("tag") && !g.tagAvailable()) {
                    return "metrics.yaml: \"tag\" needs an observation running here and none is known; use \"counter\".";
                }
                if (!ctx.supportHas("Outcomes") || !ctx.a.project.probe().hasClass("io.micrometer.core.instrument.Metrics")) {
                    return "Needs Micrometer and a logging-support version with Outcomes.";
                }
                String call = d.count().equals("tag") ? "Outcomes.tag(\"" + outcome + "\");" : "Outcomes.count(\"" + name + "\", \"" + outcome + "\");";
                if (!d.amount().isBlank()) {
                    String problem = amountProblem(ctx, g, d);
                    if (problem != null) {
                        return problem;
                    }
                    String as = d.as().isBlank() ? ctx.policy.metrics().amounts() : d.as();
                    String meter = meterWord(d.amount());
                    String other = amountMeters.putIfAbsent(name + "." + meter, as);
                    if (other != null && !other.equals(as)) {
                        return "metrics.yaml: " + name + "." + meter + " is a " + other + " elsewhere; one meter name takes one \"as\".";
                    }
                    call = "Outcomes." + (as.equals("per-run") ? "record" : "count") + "(\"" + name + "\", \"" + outcome + "\", \""
                            + meter + "\", " + g.amounts().get(d.amount()).printTrimmed() + ");";
                }
                b.edit(dev.logast.orders.Edit.insert(f.relPath(), lines[1], indent + call + (marker == null ? "" : " " + marker) + "\n"));
                b.edits(JavaEdits.imports(f, ctx.support("Outcomes")));
                ctx.dependOnStarter(b);
                return null;
            }
            default -> {
                return "metrics.yaml: \"count\" must be counter, tag or skip (got \"" + d.count() + "\").";
            }
        }
    }

    /**
     * Why a metrics.yaml "amount" cannot be written, or null. The logged value is evaluated a second time in the
     * counting line, so it must be one without side effects: a variable, a field, or a size/length/getter/record
     * accessor called on one.
     */
    private static String amountProblem(RuleContext ctx, MetricsMap.Gap g, dev.logast.apply.MetricsFile.Decision d) {
        if (ctx.policy.metrics().amounts().equals("off")) {
            return "metrics.yaml: \"amount\" is set, but metrics.amounts is off in logast.yaml.";
        }
        if (!d.count().equals("counter")) {
            return "metrics.yaml: \"amount\" needs count: counter (a tag carries no number).";
        }
        if (!d.as().isBlank() && !d.as().equals("total") && !d.as().equals("per-run")) {
            return "metrics.yaml: \"as\" must be total or per-run (got \"" + d.as() + "\").";
        }
        org.openrewrite.java.tree.Expression e = g.amounts().get(d.amount());
        if (e == null) {
            return "metrics.yaml: the line logs no number named \"" + d.amount() + "\" (it logs: "
                    + (g.amounts().isEmpty() ? "none" : String.join(", ", g.amounts().keySet())) + ").";
        }
        if (!sideEffectFree(e)) {
            return "metrics.yaml: the logged value " + e.printTrimmed() + " would be evaluated twice; put it in a variable first.";
        }
        return null;
    }

    private static boolean sideEffectFree(org.openrewrite.java.tree.Expression e) {
        if (e instanceof org.openrewrite.java.tree.J.Identifier) {
            return true;
        }
        if (e instanceof org.openrewrite.java.tree.J.FieldAccess fa) {
            return sideEffectFree(fa.getTarget());
        }
        if (e instanceof org.openrewrite.java.tree.J.MethodInvocation mi && mi.getArguments().stream()
                .allMatch(a -> a instanceof org.openrewrite.java.tree.J.Empty) && mi.getSelect() != null && sideEffectFree(mi.getSelect())) {
            String n = mi.getSimpleName();
            org.openrewrite.java.tree.JavaType.Method mt = mi.getMethodType();
            boolean accessor = mt != null && mt.getDeclaringType().getKind() == org.openrewrite.java.tree.JavaType.FullyQualified.Kind.Record;
            return n.equals("size") || n.equals("length") || n.matches("get[A-Z]\\w*") || accessor;
        }
        return false;
    }

    /** The meter-name word for a logged key: rows → rows, totalRows → total.rows. */
    static String meterWord(String key) {
        return key.replaceAll("([a-z0-9])([A-Z])", "$1.$2").replace('_', '.').toLowerCase(java.util.Locale.ROOT);
    }

    /** The lifecycle plan of the latest scan (for `logast lifecycle`). */
    public static volatile LifecyclePlan lastLifecycle;
    /** #226: this thread's last lifecycle plan (scans run in parallel in a fleet refresh). */
    public static final ThreadLocal<LifecyclePlan> LAST_LIFECYCLE = new ThreadLocal<>();

    /** MDC decisions of the latest scan (for `logast why`). */
    public static volatile List<RepeatedValueRules.Decision> lastMdcDecisions = List.of();

    public static ScanResult scan(Analysis a) {
        RuleContext ctx = new RuleContext(a);
        lastMdcDecisions = ctx.mdcDecisions;
        lastLifecycle = ctx.lifecycle();
        LAST_LIFECYCLE.set(ctx.lifecycle());
        new SetupRules(ctx).run();
        new TracingRules(ctx).run();
        new ReactiveRules(ctx).run();
        new EntrypointRules(ctx).run();
        ctx.entry.freeze();   // the entry phase ends: what it planned is read by every later rule, written by none
        ctx.freezePlan();
        new RepeatedValueRules(ctx).run();
        ctx.mdc.freeze();     // the MDC phase ends
        ctx.freezePlan();
        new ExceptionRules(ctx).run();
        new LogCallRules(ctx).run();
        new LogArgumentRules(ctx).run();
        new MessageRules(ctx).run();
        dev.logast.orders.LogCoverage measured = new CoverageRules(ctx).run();
        // the logging pack's measure: a run without it does not report it (#88)
        dev.logast.orders.LogCoverage coverage = ctx.policy.packSelected("logging") ? measured : null;
        new DbRules(ctx).run();
        new FamilyRules(ctx).run();
        new RowLoopRules(ctx).run();
        new MdcRules(ctx).run();
        new AsyncRules(ctx).run();
        new AreaRules(ctx).run();
        new MetricRules(ctx).run();
        new EventMetricRules(ctx).run();
        new CatalogRules(ctx).run();
        MetricRules.tagKeyMismatch(ctx);
        if (ctx.enabled(METRIC_OUTCOME_UNMEASURED)) {
            outcomeGaps(ctx);
        }
        new ResilienceRules(ctx).run();
        new ErrorRules(ctx).run();
        new AsyncErrorRules(ctx).run();
        new CauseRules(ctx).run();
        new CatchAllDefaultRules(ctx).run();
        new AuditRules(ctx).run();
        new VolumeRules(ctx).run();
        new KeyConflictRules(ctx).run();
        new LogIdRules(ctx).run();

        numberOrdinals(ctx.orders);
        Set<String> ids = new HashSet<>();
        List<WorkOrder> orders = new ArrayList<>();
        Map<String, String> subjects = new HashMap<>();
        for (OrderBuilder b : ctx.orders) {
            WorkOrder o = b.build();
            String base = b.anchor;
            for (int n = 2; !ids.add(o.id()); n++) {
                // two findings with one anchor (a rule's oversight) or a hash collision: never lose or crash on one;
                // the suffix follows the scan order, which is deterministic
                System.err.println("logast: internal warning: duplicate order id " + o.id() + " (anchor " + base + ")");
                b.anchor = base + "|dup" + n;
                o = b.build();
            }
            orders.add(o);
            if (b.subject != null) {
                subjects.put(o.id(), b.subject);
            }
        }
        orders.removeIf(o -> !enabled(a.policy, o.rule()));
        orders = relate(orders, subjects);
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

    /** Links orders about the same code construct (one catch, one call) to each other, by id (#57). */
    static List<WorkOrder> relate(List<WorkOrder> orders, Map<String, String> subjects) {
        Map<String, List<String>> bySubject = new HashMap<>();
        for (WorkOrder o : orders) {
            String s = subjects.get(o.id());
            if (s != null) {
                bySubject.computeIfAbsent(s, k -> new ArrayList<>()).add(o.id());
            }
        }
        List<WorkOrder> out = new ArrayList<>(orders.size());
        for (WorkOrder o : orders) {
            List<String> same = bySubject.getOrDefault(subjects.get(o.id()), List.of());
            out.add(same.size() < 2 ? o : o.withRelated(same.stream().filter(id -> !id.equals(o.id())).toList()));
        }
        return out;
    }

    static String fingerprint(Path p) {
        try {
            return Files.exists(p) ? Hashing.sha256(dev.logast.parse.SourceText.read(p)) : "absent";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Numbers orderNth orders within their group in source order: line, then the order they were found in. */
    static void numberOrdinals(List<OrderBuilder> builders) {
        Map<String, List<OrderBuilder>> groups = new LinkedHashMap<>();
        for (OrderBuilder b : builders) {
            if (b.ordinalGroup != null) {
                groups.computeIfAbsent(b.ordinalGroup, k -> new ArrayList<>()).add(b);
            }
        }
        for (List<OrderBuilder> g : groups.values()) {
            List<OrderBuilder> sorted = new ArrayList<>(g);
            sorted.sort(Comparator.comparingInt(b -> b.ordinalLine));   // stable: same line keeps the finding order
            for (int i = 0; i < sorted.size(); i++) {
                OrderBuilder b = sorted.get(i);
                b.anchor = b.anchor.substring(0, b.anchor.length() - 1) + (i + 1);
            }
        }
    }
}
