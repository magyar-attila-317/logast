package dev.logast.rules;

import java.util.Map;

import static dev.logast.rules.Rules.*;

/**
 * A few plain words per rule, for the human summary (#101): what the finding is about, without the rule id's jargon.
 * Every rule of {@link Rules#all()} has one (RuleLabelsTest).
 */
public final class RuleLabels {
    private RuleLabels() {
    }

    private static final Map<String, String> LABELS = Map.ofEntries(
            // logging
            Map.entry(SWALLOWED_EXCEPTION, "exception dropped without a log line"),
            Map.entry(LOST_THROWABLE, "log line loses the exception"),
            Map.entry(LOG_AND_RETHROW, "logged and rethrown"),
            Map.entry(EXCEPTION_LOGGED_TWICE, "exception logged twice"),
            Map.entry(STRING_CONCAT_LOG, "message built by concatenation"),
            Map.entry(LOG_FORMAT, "message not in house format"),
            Map.entry(GLOSSARY_VIOLATION, "wording off the glossary"),
            Map.entry(PII_IN_LOG, "personal data in a log line"),
            Map.entry(MDC_NOT_CLOSEABLE, "MDC.put never removed"),
            Map.entry(LOG_KEY_NESTING_CONFLICT, "field and its dotted sub-field"),
            Map.entry(MDC_KEY_ALIAS, "two MDC keys for one value"),
            Map.entry(MISSING_DOMAIN_MDC, "business id missing from MDC"),
            Map.entry(AREA_MDC_MISSING, "area id missing from MDC"),
            Map.entry(ENTRYPOINT_SILENT, "entrypoint that logs nothing"),
            Map.entry(MESSAGE_PHRASING, "message phrasing"),
            Map.entry(LOG_KEY_STYLE, "key spelling"),
            Map.entry(LOG_LEVEL, "log level"),
            Map.entry(REPEATED_LOG_VALUE, "same id in every message"),
            Map.entry(MDC_CLEAR, "MDC cleared as a whole"),
            Map.entry(MDC_NESTED_KEY, "MDC key set again inside"),
            Map.entry(MDC_NOT_OWNER, "MDC set outside its owner"),
            Map.entry(JOB_OUTCOME_MISSING, "job without an outcome line"),
            Map.entry(SKIP_UNLOGGED, "skip without a reason logged"),
            Map.entry(STATE_CHANGE_UNLOGGED, "data change not logged"),
            Map.entry(BATCH_SUMMARY_MISSING, "loop without a summary line"),
            Map.entry(DB_OUTCOME_UNLOGGED, "database outcome not logged"),
            Map.entry(MESSAGE_SUGGESTED, "generated wording"),
            Map.entry(LIFECYCLE_UNEVEN, "implementations log unevenly"),
            Map.entry(LOG_OBJECT_ARGUMENT, "whole object logged"),
            Map.entry(LOG_COLLECTION_ARGUMENT, "whole collection logged"),
            Map.entry(LOG_HELPER, "log helper"),
            Map.entry(MESSAGE_VAGUE, "vague message"),
            Map.entry(BUSINESS_EVENT_TIMESTAMP, "current time logged as a value"),
            Map.entry(LOG_KEY_VALUE_IN_MDC, "message repeats an MDC key"),
            Map.entry(SETUP_LOGBACK_XML, "hand-written logback XML"),
            Map.entry(SETUP_STRUCTURED_LOGGING, "structured log format not set up"),
            Map.entry(RUNTIME_MDC_LOST, "MDC lost at runtime"),
            Map.entry(RUNTIME_NULL_VALUE, "null logged at runtime"),
            Map.entry(LOG_IN_LOOP, "log line inside a loop"),
            Map.entry(LOG_VOLUME, "log volume"),
            Map.entry(SETUP_SUPPORT_STARTER, "logging support library"),
            Map.entry(SETUP_SERVICE_IDENTITY, "service name and version"),
            Map.entry(ASYNC_CONTEXT_LOSS, "async work loses the log context"),
            Map.entry(SCHEDULED_TASK_UNTRACED, "scheduled task without a trace"),
            Map.entry(REACTIVE_CONTEXT_LOSS, "reactive pipeline loses the log context"),
            Map.entry(SSE_BROADCAST_CONTEXT, "broadcast line without its subscriber"),
            // tracing
            Map.entry(ENTRYPOINT_NOT_OBSERVED, "entrypoint without a trace"),
            Map.entry(MANUAL_TRACE_MDC, "trace id copied into MDC by hand"),
            Map.entry(SETUP_OTEL_STARTER, "tracing not set up"),
            Map.entry(HTTP_CLIENT_UNTRACED, "HTTP client without tracing"),
            Map.entry(KAFKA_OBSERVATION_DISABLED, "Kafka without tracing"),
            Map.entry(RABBIT_OBSERVATION_DISABLED, "RabbitMQ without tracing"),
            Map.entry(JOB_LAUNCH_UNTRACED, "batch job launch without a trace"),
            Map.entry(RUNTIME_TRACE_LOST, "trace lost at runtime"),
            Map.entry(TRACING_SAMPLING, "trace sampling"),
            Map.entry(SPAN_NOT_CLOSED, "span not closed"),
            Map.entry(TRACING_EXPORT_UNCONFIGURED, "traces not exported"),
            Map.entry(SPAN_NAME_DYNAMIC, "span name built from data"),
            Map.entry(TRACE_ID_NOT_LOGGED, "trace id not in the logs"),
            Map.entry(OBSERVED_ANNOTATION_INACTIVE, "@Observed has no effect"),
            // metrics
            Map.entry(METRIC_NAMING, "metric name"),
            Map.entry(METRIC_HIGH_CARDINALITY_TAG, "metric tag with unbounded values"),
            Map.entry(METRIC_DIRECT_REGISTRY, "meter registered by hand"),
            Map.entry(METRIC_TAG_KEY, "metric tag key"),
            Map.entry(METRIC_TAG_VALUE, "metric tag value"),
            Map.entry(METRIC_REGISTERED_PER_CALL, "meter created on every call"),
            Map.entry(METRIC_GAUGE_WEAK_REF, "gauge that can vanish"),
            Map.entry(BUSINESS_EVENT_UNMEASURED, "business event not measured"),
            Map.entry(METRIC_TAG_KEYS_MISMATCH, "one meter, different tags"),
            Map.entry(METRIC_OUTCOME_UNMEASURED, "logged outcome not counted"),
            Map.entry(METRIC_CATALOG, "metric name at the call site"),
            // resilience
            Map.entry(HTTP_CLIENT_NO_TIMEOUT, "HTTP client without a timeout"),
            Map.entry(KAFKA_NO_ERROR_HANDLER, "Kafka without an error handler"),
            Map.entry(UNBOUNDED_EXECUTOR, "unbounded thread pool"),
            Map.entry(BLOCKING_WAIT_UNBOUNDED, "wait without a timeout"),
            Map.entry(ROW_BY_ROW_WRITE, "one write per row"),
            Map.entry(DB_NO_TIMEOUT, "transactions without a timeout"),
            Map.entry(REMOTE_CALL_IN_TRANSACTION, "remote call inside a transaction"),
            Map.entry(SCHEDULED_WITHOUT_LOCK, "scheduled job without a lock"),
            Map.entry(RETRY_INACTIVE, "retry that never runs"),
            Map.entry(SCHEDULER_SINGLE_THREAD, "scheduler with one thread"),
            Map.entry(SHUTDOWN_NOT_GRACEFUL, "shutdown not graceful"),
            Map.entry(HEALTH_PROBE_MISCONFIGURED, "health probes"),
            Map.entry(READINESS_WITHOUT_DATABASE, "readiness ignores the database"),
            Map.entry(CACHE_UNBOUNDED, "cache without a limit"),
            Map.entry(RABBIT_NO_ERROR_HANDLER, "RabbitMQ without an error handler"),
            Map.entry(RETRY_IN_TRANSACTION, "retry inside a transaction"),
            Map.entry(RETRY_SETTINGS_HARDCODED, "retry settings in code"),
            Map.entry(RETRY_RECOMMENDED, "idempotent call without retry"),
            Map.entry(NESTED_RETRY, "retry inside a retry"),
            Map.entry(RETRY_BYPASSED, "retry the proxy never sees"),
            // errors
            Map.entry(ERROR_DETAILS_EXPOSED, "error details sent to clients"),
            Map.entry(ERROR_RESPONSE_UNTRACED, "error response without a trace id"),
            Map.entry(ASYNC_ERROR_LOST, "async error lost"),
            Map.entry(CAUSE_DROPPED, "exception cause dropped"),
            Map.entry(CATCH_ALL_DEFAULT, "failure turned into a default value"),
            Map.entry(UNMAPPED_EXCEPTION, "exception without an HTTP status"),
            Map.entry(CLIENT_ERROR_AS_SERVER_ERROR, "client error answered as 500"),
            Map.entry(PROBLEM_DETAILS_DISABLED, "error responses not ProblemDetail"),
            Map.entry(UNEXPECTED_ERROR_UNLOGGED, "unexpected error not logged"),
            Map.entry(ERROR_HANDLER_STATUS_OK, "error handler answers 200"),
            Map.entry(TX_COMMITS_ON_CHECKED, "transaction commits on an exception"),
            Map.entry(REQUEST_NOT_VALIDATED, "request body not validated"),
            // audit
            Map.entry(AUDIT_MISSING, "action without an audit record"),
            Map.entry(AUDIT_SECURITY_EVENTS, "security events not audited"),
            Map.entry(AUDIT_FAILURES_UNRECORDED, "failed actions not audited"),
            Map.entry(AUDIT_RECORD_INCOMPLETE, "incomplete audit record"),
            Map.entry(AUDIT_LOG_MIXED, "audit mixed into the application log"),
            Map.entry(DATA_CHANGES_UNAUDITED, "data changes not audited"),
            Map.entry(AUDIT_LOGGER_UNROUTED, "audit logger not routed"),
            Map.entry(AUDIT_BEFORE_COMMIT, "audit written before commit"),
            Map.entry(AUDIT_RECORD_SECRET, "secret in an audit record"),
            Map.entry(AUDIT_ACTOR_LOST_ASYNC, "audit loses the user in async work"));

    /** The rule's label, or the id itself for a rule without one. */
    public static String of(String rule) {
        return LABELS.getOrDefault(rule, rule);
    }

    static boolean has(String rule) {
        return LABELS.containsKey(rule);
    }
}
