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
    public static final String AREA_MDC_MISSING = "AREA_MDC_MISSING";
    public static final String LOG_LEVEL = "LOG_LEVEL";
    public static final String EXCEPTION_LOGGED_TWICE = "EXCEPTION_LOGGED_TWICE";
    public static final String HTTP_CLIENT_UNTRACED = "HTTP_CLIENT_UNTRACED";
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

    /** Rule → packs it belongs to. A rule runs when any of its packs is selected (policy.packs / --pack). */
    private static final Map<String, Set<String>> PACKS = new HashMap<>();

    static {
        for (String r : List.of(SWALLOWED_EXCEPTION, LOST_THROWABLE, LOG_AND_RETHROW, EXCEPTION_LOGGED_TWICE,
                STRING_CONCAT_LOG, LOG_FORMAT, GLOSSARY_VIOLATION, PII_IN_LOG, MDC_NOT_CLOSEABLE, MDC_KEY_ALIAS,
                MISSING_DOMAIN_MDC, AREA_MDC_MISSING, ENTRYPOINT_SILENT, MESSAGE_PHRASING, LOG_KEY_STYLE, LOG_LEVEL,
                SETUP_LOGBACK_XML, SETUP_STRUCTURED_LOGGING, RUNTIME_MDC_LOST, RUNTIME_NULL_VALUE, LOG_IN_LOOP, LOG_VOLUME)) {
            PACKS.put(r, Set.of("logging"));
        }
        for (String r : List.of(ENTRYPOINT_NOT_OBSERVED, MANUAL_TRACE_MDC, SETUP_OTEL_STARTER, HTTP_CLIENT_UNTRACED,
                KAFKA_OBSERVATION_DISABLED, JOB_LAUNCH_UNTRACED, RUNTIME_TRACE_LOST)) {
            PACKS.put(r, Set.of("tracing"));
        }
        // context propagation matters to MDC (logging) and spans (tracing); the starter serves every pack
        PACKS.put(ASYNC_CONTEXT_LOSS, Set.of("logging", "tracing"));
        for (String r : List.of(METRIC_NAMING, METRIC_HIGH_CARDINALITY_TAG, METRIC_DIRECT_REGISTRY)) {
            PACKS.put(r, Set.of("metrics"));
        }
        PACKS.put(SETUP_SUPPORT_STARTER, Set.of("logging", "tracing", "metrics"));
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
            Map.entry(MESSAGE_PHRASING, "low"), Map.entry(MDC_KEY_ALIAS, "medium"), Map.entry(LOG_KEY_STYLE, "low"),
            Map.entry(AREA_MDC_MISSING, "medium"), Map.entry(LOG_LEVEL, "medium"), Map.entry(EXCEPTION_LOGGED_TWICE, "medium"),
            Map.entry(HTTP_CLIENT_UNTRACED, "high"), Map.entry(KAFKA_OBSERVATION_DISABLED, "high"), Map.entry(JOB_LAUNCH_UNTRACED, "high"),
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

    public static ScanResult scan(Analysis a) {
        RuleContext ctx = new RuleContext(a);
        new SetupRules(ctx).run();
        new TracingRules(ctx).run();
        new EntrypointRules(ctx).run();
        ctx.freezePlan();
        new ExceptionRules(ctx).run();
        new LogCallRules(ctx).run();
        new MdcRules(ctx).run();
        new AsyncRules(ctx).run();
        new AreaRules(ctx).run();
        new MetricRules(ctx).run();
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
        return new ScanResult(1, a.project.root().toString(), a.stats(), fingerprints, orders);
    }

    static String fingerprint(Path p) {
        try {
            return Files.exists(p) ? Hashing.sha256(Files.readString(p)) : "absent";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
