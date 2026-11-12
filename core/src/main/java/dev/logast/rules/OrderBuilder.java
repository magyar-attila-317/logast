package dev.logast.rules;

import dev.logast.orders.Edit;
import dev.logast.orders.Slot;
import dev.logast.orders.WorkOrder;
import dev.logast.parse.Hashing;

import java.util.*;

/** Mutable builder; the id is derived from rule + anchor so it survives rescans of unchanged code. */
public final class OrderBuilder {
    private static final Map<String, String> PREFIX = Map.ofEntries(
            Map.entry(Rules.SWALLOWED_EXCEPTION, "SWA"), Map.entry(Rules.LOST_THROWABLE, "LTH"),
            Map.entry(Rules.LOG_AND_RETHROW, "LAR"), Map.entry(Rules.STRING_CONCAT_LOG, "CAT"),
            Map.entry(Rules.LOG_FORMAT, "FMT"), Map.entry(Rules.GLOSSARY_VIOLATION, "GLO"),
            Map.entry(Rules.PII_IN_LOG, "PII"), Map.entry(Rules.MDC_NOT_CLOSEABLE, "MDC"),
            Map.entry(Rules.MANUAL_TRACE_MDC, "TRC"), Map.entry(Rules.MISSING_DOMAIN_MDC, "DOM"),
            Map.entry(Rules.ENTRYPOINT_NOT_OBSERVED, "OBS"), Map.entry(Rules.ENTRYPOINT_SILENT, "SIL"),
            Map.entry(Rules.ASYNC_CONTEXT_LOSS, "ASY"), Map.entry(Rules.SETUP_OTEL_STARTER, "OTL"),
            Map.entry(Rules.SETUP_SUPPORT_STARTER, "SUP"), Map.entry(Rules.SETUP_LOGBACK_XML, "LBK"),
            Map.entry(Rules.SETUP_STRUCTURED_LOGGING, "SLG"), Map.entry(Rules.MESSAGE_PHRASING, "PHR"),
            Map.entry(Rules.MDC_KEY_ALIAS, "MKA"), Map.entry(Rules.LOG_KEY_STYLE, "LKS"),
            Map.entry(Rules.AREA_MDC_MISSING, "AMM"), Map.entry(Rules.LOG_LEVEL, "LVL"),
            Map.entry(Rules.EXCEPTION_LOGGED_TWICE, "ELT"), Map.entry(Rules.HTTP_CLIENT_UNTRACED, "HTC"),
            Map.entry(Rules.KAFKA_OBSERVATION_DISABLED, "KFK"), Map.entry(Rules.JOB_LAUNCH_UNTRACED, "JOB"),
            Map.entry(Rules.RUNTIME_TRACE_LOST, "RTL"), Map.entry(Rules.RUNTIME_MDC_LOST, "RML"), Map.entry(Rules.RUNTIME_NULL_VALUE, "RNV"),
            Map.entry(Rules.LOG_IN_LOOP, "LIL"), Map.entry(Rules.LOG_VOLUME, "VOL"),
            Map.entry(Rules.METRIC_NAMING, "MTN"), Map.entry(Rules.METRIC_HIGH_CARDINALITY_TAG, "MTC"),
            Map.entry(Rules.METRIC_DIRECT_REGISTRY, "MTD"));

    final String rule;
    final String anchor;
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

    WorkOrder build() {
        return new WorkOrder(id(), rule, List.copyOf(fixes), severity, title, file, line, List.copyOf(locations),
                method, paths, suggestion, List.copyOf(edits), Map.copyOf(slots), context, List.copyOf(dependsOn));
    }
}
