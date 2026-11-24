package dev.logast.rules;

import dev.logast.orders.Edit;
import dev.logast.orders.Slot;
import dev.logast.orders.WorkOrder;
import dev.logast.parse.Hashing;

import java.util.*;

/** Mutable builder; the id is derived from rule + anchor so it survives rescans of unchanged code. */
public final class OrderBuilder {
    /** Rule → order id prefix (package-private for RuleLedgerTest, which reads applied ids in e2e snapshots). */
    /** The rule an order id's prefix stands for ({@code BET} → BUSINESS_EVENT_TIMESTAMP), or null. */
    public static String ruleOfPrefix(String prefix) {
        for (var e : PREFIX.entrySet()) {
            if (e.getValue().equals(prefix)) {
                return e.getKey();
            }
        }
        return null;
    }

    static final Map<String, String> PREFIX = Map.ofEntries(
            Map.entry(Rules.SWALLOWED_EXCEPTION, "SWA"), Map.entry(Rules.LOST_THROWABLE, "LTH"),
            Map.entry(Rules.LOG_AND_RETHROW, "LAR"), Map.entry(Rules.STRING_CONCAT_LOG, "CAT"),
            Map.entry(Rules.LOG_FORMAT, "FMT"), Map.entry(Rules.GLOSSARY_VIOLATION, "GLO"),
            Map.entry(Rules.PII_IN_LOG, "PII"), Map.entry(Rules.MDC_NOT_CLOSEABLE, "MDC"),
            Map.entry(Rules.MANUAL_TRACE_MDC, "TRC"), Map.entry(Rules.TRACE_KEY_HOLDS_APP_ID, "TKA"), Map.entry(Rules.MDC_KEY_NOT_IN_LOG_OUTPUT, "MKO"), Map.entry(Rules.MISSING_DOMAIN_MDC, "DOM"),
            Map.entry(Rules.ENTRYPOINT_NOT_OBSERVED, "OBS"), Map.entry(Rules.ENTRYPOINT_SILENT, "SIL"),
            Map.entry(Rules.ASYNC_CONTEXT_LOSS, "ASY"), Map.entry(Rules.SETUP_OTEL_STARTER, "OTL"),
            Map.entry(Rules.SETUP_SUPPORT_STARTER, "SUP"), Map.entry(Rules.SETUP_LOGBACK_XML, "LBK"),
            Map.entry(Rules.SETUP_STRUCTURED_LOGGING, "SLG"), Map.entry(Rules.MESSAGE_PHRASING, "PHR"),
            Map.entry(Rules.MDC_KEY_ALIAS, "MKA"), Map.entry(Rules.LOG_KEY_NESTING_CONFLICT, "LKN"), Map.entry(Rules.LOG_ID_MISSING, "LIM"), Map.entry(Rules.LOG_KEY_STYLE, "LKS"), Map.entry(Rules.REPEATED_LOG_VALUE, "RLV"),
            Map.entry(Rules.JOB_OUTCOME_MISSING, "OUT"), Map.entry(Rules.SKIP_UNLOGGED, "SKP"),
            Map.entry(Rules.STATE_CHANGE_UNLOGGED, "STC"), Map.entry(Rules.BATCH_SUMMARY_MISSING, "BSM"),
            Map.entry(Rules.DB_OUTCOME_UNLOGGED, "DBO"), Map.entry(Rules.SETUP_SERVICE_IDENTITY, "SID"), Map.entry(Rules.LIFECYCLE_UNEVEN, "LCU"), Map.entry(Rules.ROW_BY_ROW_WRITE, "RBR"),
            Map.entry(Rules.METRIC_OUTCOME_UNMEASURED, "OUM"), Map.entry(Rules.MESSAGE_SUGGESTED, "SUG"),
            Map.entry(Rules.MDC_CLEAR, "MCL"), Map.entry(Rules.MDC_NOT_OWNER, "MNO"), Map.entry(Rules.MDC_NESTED_KEY, "MNK"), Map.entry(Rules.LOG_OBJECT_ARGUMENT, "LOA"),
            Map.entry(Rules.LOG_COLLECTION_ARGUMENT, "LCA"), Map.entry(Rules.LOG_HELPER, "LHL"), Map.entry(Rules.MESSAGE_VAGUE, "MVG"),
            Map.entry(Rules.BUSINESS_EVENT_TIMESTAMP, "BET"),
            Map.entry(Rules.LOG_KEY_VALUE_IN_MDC, "KVM"),
            Map.entry(Rules.AREA_MDC_MISSING, "AMM"), Map.entry(Rules.LOG_LEVEL, "LVL"),
            Map.entry(Rules.EXCEPTION_LOGGED_TWICE, "ELT"), Map.entry(Rules.HTTP_CLIENT_UNTRACED, "HTC"),
            Map.entry(Rules.TRACING_SAMPLING, "SMP"), Map.entry(Rules.METRIC_TAG_KEY, "MTK"), Map.entry(Rules.METRIC_TAG_VALUE, "MTV"),
            Map.entry(Rules.METRIC_REGISTERED_PER_CALL, "MRC"), Map.entry(Rules.METRIC_GAUGE_WEAK_REF, "MGW"),
            Map.entry(Rules.BUSINESS_EVENT_UNMEASURED, "BEU"), Map.entry(Rules.METRIC_TAG_KEYS_MISMATCH, "MTM"), Map.entry(Rules.METRIC_CATALOG, "MCA"),
            Map.entry(Rules.HTTP_CLIENT_NO_TIMEOUT, "HTO"), Map.entry(Rules.KAFKA_NO_ERROR_HANDLER, "KEH"),
            Map.entry(Rules.UNBOUNDED_EXECUTOR, "UEX"), Map.entry(Rules.BLOCKING_WAIT_UNBOUNDED, "BWU"),
            Map.entry(Rules.ERROR_DETAILS_EXPOSED, "EDE"), Map.entry(Rules.ERROR_RESPONSE_UNTRACED, "ERT"), Map.entry(Rules.DB_NO_TIMEOUT, "DBT"), Map.entry(Rules.REMOTE_CALL_IN_TRANSACTION, "RCT"), Map.entry(Rules.SCHEDULED_WITHOUT_LOCK, "SWL"), Map.entry(Rules.RETRY_INACTIVE, "RTI"), Map.entry(Rules.SCHEDULER_SINGLE_THREAD, "SST"), Map.entry(Rules.SHUTDOWN_NOT_GRACEFUL, "SNG"), Map.entry(Rules.HEALTH_PROBE_MISCONFIGURED, "HPM"), Map.entry(Rules.READINESS_WITHOUT_DATABASE, "RWD"), Map.entry(Rules.CACHE_UNBOUNDED, "CUB"), Map.entry(Rules.RABBIT_NO_ERROR_HANDLER, "REH"), Map.entry(Rules.RETRY_IN_TRANSACTION, "RIT"), Map.entry(Rules.RETRY_SETTINGS_HARDCODED, "RSH"), Map.entry(Rules.RETRY_RECOMMENDED, "RRC"), Map.entry(Rules.NESTED_RETRY, "NRT"), Map.entry(Rules.RETRY_BYPASSED, "RBP"), Map.entry(Rules.ASYNC_ERROR_LOST, "AEL"), Map.entry(Rules.CAUSE_DROPPED, "CDR"), Map.entry(Rules.CATCH_ALL_DEFAULT, "CAD"), Map.entry(Rules.AUDIT_FAILURES_UNRECORDED, "AFU"), Map.entry(Rules.AUDIT_RECORD_INCOMPLETE, "ARI"), Map.entry(Rules.AUDIT_LOG_MIXED, "ALM"), Map.entry(Rules.DATA_CHANGES_UNAUDITED, "DCU"), Map.entry(Rules.UNMAPPED_EXCEPTION, "UMX"),
            Map.entry(Rules.CLIENT_ERROR_AS_SERVER_ERROR, "CES"), Map.entry(Rules.PROBLEM_DETAILS_DISABLED, "PDD"),
            Map.entry(Rules.UNEXPECTED_ERROR_UNLOGGED, "UEU"), Map.entry(Rules.ERROR_HANDLER_STATUS_OK, "EHO"), Map.entry(Rules.TX_COMMITS_ON_CHECKED, "TCC"),
            Map.entry(Rules.REQUEST_NOT_VALIDATED, "RQV"),
            Map.entry(Rules.AUDIT_MISSING, "AUM"), Map.entry(Rules.AUDIT_SECURITY_EVENTS, "AUS"),
            Map.entry(Rules.AUDIT_LOGGER_UNROUTED, "ALU"), Map.entry(Rules.AUDIT_BEFORE_COMMIT, "ABC"), Map.entry(Rules.AUDIT_RECORD_SECRET, "ARS"),
            Map.entry(Rules.AUDIT_ACTOR_LOST_ASYNC, "AAL"), Map.entry(Rules.SCHEDULED_TASK_UNTRACED, "STU"),
            Map.entry(Rules.KAFKA_OBSERVATION_DISABLED, "KFK"), Map.entry(Rules.RABBIT_OBSERVATION_DISABLED, "RBT"), Map.entry(Rules.JOB_LAUNCH_UNTRACED, "JOB"),
            Map.entry(Rules.OBSERVED_ANNOTATION_INACTIVE, "OBA"), Map.entry(Rules.SPAN_NOT_CLOSED, "SNC"), Map.entry(Rules.TRACING_EXPORT_UNCONFIGURED, "TEX"),
            Map.entry(Rules.SPAN_NAME_DYNAMIC, "SND"), Map.entry(Rules.TRACE_ID_NOT_LOGGED, "TNL"),
            Map.entry(Rules.SSE_BROADCAST_CONTEXT, "SBC"),
            Map.entry(Rules.REACTIVE_CONTEXT_LOSS, "RCL"),
            Map.entry(Rules.RUNTIME_TRACE_LOST, "RTL"), Map.entry(Rules.RUNTIME_MDC_LOST, "RML"), Map.entry(Rules.RUNTIME_NULL_VALUE, "RNV"),
            Map.entry(Rules.LOG_IN_LOOP, "LIL"), Map.entry(Rules.LOG_VOLUME, "VOL"),
            Map.entry(Rules.METRIC_NAMING, "MTN"), Map.entry(Rules.METRIC_HIGH_CARDINALITY_TAG, "MTC"),
            Map.entry(Rules.METRIC_DIRECT_REGISTRY, "MTD"));

    final String rule;
    String anchor;
    /** Set by {@link RuleContext#orderNth}: the anchor is this group plus the order's place in it, assigned at build time. */
    String ordinalGroup;
    int ordinalLine;
    final List<String> fixes = new ArrayList<>();
    String severity;
    String title;
    String file;
    int line;
    final List<WorkOrder.Location> locations = new ArrayList<>();
    String method;
    List<String> paths = List.of();
    String suggestion;
    final List<Edit> edits = new ArrayList<>();

    /** Drops the edits again (a default that could not be written: the order stays a decision to make). */
    void clearEdits() {
        edits.clear();
    }
    final Map<String, Slot> slots = new LinkedHashMap<>();
    WorkOrder.Context context = WorkOrder.Context.EMPTY;
    final Set<String> dependsOn = new LinkedHashSet<>();

    OrderBuilder(String rule, String anchor, String severity) {
        this.rule = rule;
        this.anchor = anchor;
        this.severity = severity;
        this.fixes.add(rule);
    }

    public String id() {
        return PREFIX.getOrDefault(rule, "ORD") + "-" + Hashing.shortId(rule + "|" + anchor);
    }

    public OrderBuilder fix(String rule) {
        if (!fixes.contains(rule)) {
            fixes.add(rule);
        }
        return this;
    }

    public OrderBuilder at(String file, int line) {
        this.file = file;
        this.line = line;
        return this;
    }

    public OrderBuilder title(String title) {
        this.title = title;
        return this;
    }

    public OrderBuilder suggestion(String s) {
        this.suggestion = s;
        return this;
    }

    public OrderBuilder edit(Edit e) {
        if (e != null && !edits.contains(e)) {
            edits.add(e);
        }
        return this;
    }

    public OrderBuilder edits(Collection<Edit> es) {
        es.forEach(this::edit);
        return this;
    }

    public OrderBuilder slot(String name, Slot slot) {
        slots.put(name, slot);
        return this;
    }

    public OrderBuilder location(String file, int line, String rule) {
        locations.add(new WorkOrder.Location(file, line, rule));
        fix(rule);
        return this;
    }

    public OrderBuilder dependsOn(String id) {
        if (id != null) {
            dependsOn.add(id);
        }
        return this;
    }

    public OrderBuilder context(WorkOrder.Context c) {
        this.context = c;
        return this;
    }

    public OrderBuilder severity(String s) {
        this.severity = s;
        return this;
    }

    private final Map<String, Object> data = new LinkedHashMap<>();

    /** Replaces text in every edit added so far (turns a slot template into generated text). */
    OrderBuilder rewriteEdits(String from, String to) {
        edits.replaceAll(e -> e.text().contains(from) ? new Edit(e.file(), e.kind(), e.start(), e.end(), e.text().replace(from, to)) : e);
        return this;
    }

    String basis;
    String doneWhen;
    /** The code construct the finding is about (a catch, a call), as file@offset: orders on one subject are related. */
    String subject;

    /** The evidence the rule decided on, where the title does not show it: one short line. */
    public OrderBuilder basis(String s) {
        this.basis = s;
        return this;
    }

    /** The observable condition after which the next scan no longer reports it: one short line. */
    public OrderBuilder doneWhen(String s) {
        this.doneWhen = s;
        return this;
    }

    /** The finding is about this tree in this file: other orders about the same tree are listed as related. */
    public OrderBuilder about(String file, org.openrewrite.Tree tree) {
        int start = dev.logast.parse.Pos.start(tree);
        this.subject = start < 0 ? null : file + "@" + start;
        return this;
    }

    public OrderBuilder data(String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
        return this;
    }

    WorkOrder build() {
        return new WorkOrder(id(), rule, List.copyOf(fixes), severity, title, file, line, List.copyOf(locations),
                method, paths, suggestion, List.copyOf(edits), Map.copyOf(slots), context, List.copyOf(dependsOn),
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(data)), basis, doneWhen, List.of());
    }
}
