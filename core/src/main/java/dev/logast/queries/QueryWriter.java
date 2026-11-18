package dev.logast.queries;

import dev.logast.policy.Policy;
import dev.logast.queries.ServiceSignals.Flow;
import dev.logast.queries.ServiceSignals.Meter;
import dev.logast.queries.ServiceSignals.Timing;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Writes {@link ServiceSignals} as queries to paste (#106): PromQL, NRQL and Splunk SPL, one code block per query
 * (SPL has no line comments, so every description sits above its block). Names follow what the libraries write:
 * <ul>
 * <li>Prometheus: Micrometer's PrometheusNamingConvention (dots to underscores; a timer is {@code _seconds_count},
 * {@code _seconds_sum}, {@code _seconds_max}, {@code _seconds_bucket} only with percentile histograms; a counter
 * {@code _total}). Sources: Micrometer reference › Prometheus; PrometheusNamingConvention.</li>
 * <li>Failure tags: {@code http.server.requests} and {@code tasks.scheduled.execution} carry {@code outcome}
 * (SERVER_ERROR / ERROR); every Observation timer carries {@code error} ("none" when it succeeded). Sources: Spring
 * Framework reference › Observability Support; Micrometer DefaultMeterObservationHandler.</li>
 * <li>New Relic: Micrometer data over OTLP lands in {@code Metric} with {@code service.name}; a timer is a
 * distribution, a counter a count. Sources: New Relic docs › Micrometer metrics registry, Metric data type.</li>
 * <li>Log fields per Spring Boot structured format (ecs / logstash / gelf); MDC entries keep their key (GELF: with a
 * leading {@code _}), the trace id is the MDC key {@code traceId}. Source: Spring Boot reference › Logging ›
 * Structured Logging, and its formatters.</li>
 * </ul>
 */
public final class QueryWriter {
    private QueryWriter() {
    }

    /** @param hints what the user should know (a backend left out and why) */
    public record Result(String text, String summary, List<String> hints) {
    }

    /** Log field names of one structured format; {@code errorType} null: the format has no exception-type field. */
    record LogFields(String level, String logger, String message, String service, String errorType, String stack,
                     String mdcPrefix) {
        static LogFields of(String format) {
            return switch (format) {
                case "logstash" -> new LogFields("level", "logger_name", "message", "service.name", null, "stack_trace", "");
                case "gelf" -> new LogFields("_level_name", "_log_logger", "short_message", "host", "_error_type",
                        "_error_stack_trace", "_");
                default -> new LogFields("log.level", "log.logger", "message", "service.name", "error.type", "error.stack_trace", "");
            };
        }
    }

    /** Collects the markdown and counts the queries. */
    private static final class Doc {
        final StringBuilder out = new StringBuilder();
        int count;

        Doc text(String s) {
            out.append(s);
            return this;
        }

        void query(String lang, String description, String query) {
            out.append('\n').append(description).append("\n\n```").append(lang).append('\n').append(query).append("\n```\n");
            count++;
        }
    }

    public static Result markdown(ServiceSignals s, Policy.Queries q) {
        Doc d = new Doc();
        List<String> hints = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        d.text("# Queries for " + s.service() + "\n\nWritten by `logast queries` from the code as it stands; run it again "
                + "after `logast apply`. Time ranges and thresholds are starting points.\n");
        int before = d.count;
        if (q.backends().contains("prometheus")) {
            prometheus(s, d);
            counts.put("PromQL", d.count - before);
            before = d.count;
        }
        if (q.backends().contains("newrelic")) {
            newRelic(s, d);
            counts.put("NRQL", d.count - before);
            before = d.count;
        }
        if (q.backends().contains("splunk")) {
            if (q.splunk().index().isEmpty()) {
                hints.add("no Splunk queries: set queries.splunk.index in logast.yaml (the index the service logs to)");
            } else {
                splunk(s, q.splunk(), d);
                counts.put("SPL", d.count - before);
            }
        }
        String summary = counts.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).collect(Collectors.joining(", "));
        return new Result(d.out.toString(), summary.isEmpty() ? "nothing to write" : summary, hints);
    }

    /** Timers from ObservedContext entrypoints and from code (observations, Timer builders, @Timed / @Observed). */
    private static SortedSet<String> timers(ServiceSignals s) {
        SortedSet<String> names = new TreeSet<>();
        s.flows().stream().filter(f -> f.timing() == Timing.OBSERVATION).forEach(f -> names.add(f.metric()));
        s.meters().stream().filter(m -> m.type().equals("observation") || m.type().equals("timer")).forEach(m -> names.add(m.name()));
        return names;
    }

    private static List<Meter> business(ServiceSignals s) {
        return s.meters().stream().filter(m -> m.type().equals("counter") || m.type().equals("summary")).toList();
    }

    // ---- Prometheus ------------------------------------------------------------------------------------

    static String prom(String name) {
        return name.replaceAll("[^a-zA-Z0-9_:]", "_");
    }

    private static String sel(ServiceSignals s, String... extra) {
        List<String> parts = new ArrayList<>();
        parts.add(s.promServiceLabel() + "=\"" + s.service() + "\"");
        parts.addAll(List.of(extra));
        return "{" + String.join(", ", parts) + "}";
    }

    private static void prometheus(ServiceSignals s, Doc d) {
        d.text("\n## Prometheus (PromQL)\n\nEvery meter carries `" + s.promServiceLabel() + "=\"" + s.service() + "\"`"
                + (s.promServiceLabel().equals("job") ? " (the scrape job's name; set management.metrics.tags.application, or "
                + "queries.prometheus.serviceLabel in logast.yaml, for a tag of the service's own)" : "")
                + ". Percentiles need `management.metrics.distribution.percentiles-histogram.<meter>: true`; without it "
                + "there is no `_bucket` series.\n");
        promFamily(s, d, Timing.HTTP, "HTTP endpoints", "http.server.requests", List.of("method", "uri"), "outcome=\"SERVER_ERROR\"");
        promFamily(s, d, Timing.SCHEDULED, "Scheduled jobs", "tasks.scheduled.execution", List.of("code.namespace", "code.function"),
                "outcome=\"ERROR\"");
        promFamily(s, d, Timing.KAFKA, "Kafka listeners", "spring.kafka.listener", List.of("spring.kafka.listener.id"), "error!=\"none\"");
        SortedSet<String> timers = timers(s);
        if (!timers.isEmpty()) {
            d.text("\n### Observed flows and timers\n\n`error` is the exception's class name, `none` when the call succeeded.\n");
            for (String name : timers) {
                String base = prom(name) + "_seconds";
                d.text("\n#### " + name + flowsOf(s, name) + "\n");
                d.query("promql", "Calls per second:", "sum(rate(" + base + "_count" + sel(s) + "[5m]))");
                d.query("promql", "Failures per second, by exception:",
                        "sum by (error) (rate(" + base + "_count" + sel(s, "error!=\"none\"") + "[5m]))");
                d.query("promql", "Average duration (seconds):",
                        "sum(rate(" + base + "_sum" + sel(s) + "[5m])) / sum(rate(" + base + "_count" + sel(s) + "[5m]))");
                d.query("promql", "Slowest call in the last minutes (seconds):", "max(" + base + "_max" + sel(s) + ")");
            }
        }
        List<Meter> business = business(s);
        if (!business.isEmpty()) {
            d.text("\n### Business outcomes\n");
            for (Meter m : business) {
                String by = m.tags().contains("outcome") ? "sum by (outcome) " : "sum ";
                String where = "  \n`" + m.file() + ":" + m.line() + "`";
                if (m.type().equals("counter")) {
                    d.query("promql", "**" + m.name() + "**" + outcomesOf(m) + ", per hour" + where,
                            by + "(increase(" + prom(m.name()) + "_total" + sel(s) + "[1h]))");
                } else {
                    d.query("promql", "**" + m.name() + "**" + outcomesOf(m) + ", average per run" + where,
                            by + "(rate(" + prom(m.name()) + "_sum" + sel(s) + "[1h])) / " + by + "(rate(" + prom(m.name())
                                    + "_count" + sel(s) + "[1h]))");
                }
            }
        }
    }

    private static void promFamily(ServiceSignals s, Doc d, Timing timing, String title, String metric, List<String> by, String failed) {
        List<Flow> flows = s.flows().stream().filter(f -> f.timing() == timing).toList();
        if (flows.isEmpty()) {
            return;
        }
        String base = prom(metric) + "_seconds";
        String g = "sum by (" + by.stream().map(QueryWriter::prom).collect(Collectors.joining(", ")) + ") ";
        d.text("\n### " + title + " (`" + metric + "`)\n");
        d.query("promql", "Calls per second:", g + "(rate(" + base + "_count" + sel(s) + "[5m]))");
        d.query("promql", "Share of failed calls (0 to 1):", g + "(rate(" + base + "_count" + sel(s, failed) + "[5m]))\n  / "
                + g + "(rate(" + base + "_count" + sel(s) + "[5m]))");
        d.query("promql", "Average duration (seconds):", g + "(rate(" + base + "_sum" + sel(s) + "[5m]))\n  / " + g + "(rate("
                + base + "_count" + sel(s) + "[5m]))");
        d.query("promql", "p95 duration (seconds; needs the percentiles histogram):", "histogram_quantile(0.95, sum by (le, "
                + by.stream().map(QueryWriter::prom).collect(Collectors.joining(", ")) + ") (rate(" + base + "_bucket" + sel(s) + "[5m])))");
        if (flows.stream().anyMatch(f -> !f.tags().isEmpty())) {
            d.text("\nFor one of them, add its labels to the selector:\n\n| entrypoint | labels |\n|---|---|\n");
            for (Flow f : flows) {
                String labels = f.tags().entrySet().stream().map(e -> prom(e.getKey()) + "=\"" + e.getValue() + "\"")
                        .collect(Collectors.joining(", "));
                d.text("| " + cell(f.label()) + " | `" + cell(labels) + "` |\n");
            }
        }
    }

    // ---- New Relic ---------------------------------------------------------------------------------------

    /** A metric or attribute name NRQL takes as it is, else in backticks ({@code pledge-messages.on-batch}). */
    static String nr(String name) {
        return name.matches("[A-Za-z_][A-Za-z0-9_.]*") ? name : "`" + name + "`";
    }

    private static String nrWhere(ServiceSignals s) {
        return "WHERE service.name = '" + s.service() + "'";
    }

    private static void newRelic(ServiceSignals s, Doc d) {
        d.text("\n## New Relic (NRQL)\n\nMetrics as Micrometer sends them over OTLP, New Relic's recommended route (resource "
                + "attribute `service.name`, delta temporality): a timer is a distribution, a counter a count. Durations are "
                + "in the unit the registry sends.\n");
        nrFamily(s, d, Timing.HTTP, "HTTP endpoints", "http.server.requests", "method, uri", "outcome = 'SERVER_ERROR'");
        nrFamily(s, d, Timing.SCHEDULED, "Scheduled jobs", "tasks.scheduled.execution", "code.namespace, code.function", "outcome = 'ERROR'");
        nrFamily(s, d, Timing.KAFKA, "Kafka listeners", "spring.kafka.listener", "spring.kafka.listener.id", "error != 'none'");
        SortedSet<String> timers = timers(s);
        if (!timers.isEmpty()) {
            d.text("\n### Observed flows and timers\n");
            for (String name : timers) {
                String m = nr(name);
                d.query("sql", "**" + name + "**" + flowsOf(s, name) + ": calls, failures, average and slowest duration",
                        "FROM Metric SELECT count(" + m + "), filter(count(" + m + "), WHERE error != 'none') AS 'failed', average("
                                + m + "), max(" + m + ") " + nrWhere(s) + " TIMESERIES");
            }
        }
        List<Meter> business = business(s);
        if (!business.isEmpty()) {
            d.text("\n### Business outcomes\n");
            for (Meter mt : business) {
                String m = nr(mt.name());
                String facet = mt.tags().contains("outcome") ? " FACET outcome" : "";
                d.query("sql", "**" + mt.name() + "**" + outcomesOf(mt) + (mt.type().equals("counter") ? ", per hour" : ", per run"),
                        mt.type().equals("counter")
                                ? "FROM Metric SELECT sum(" + m + ") " + nrWhere(s) + facet + " TIMESERIES 1 hour"
                                : "FROM Metric SELECT average(" + m + "), max(" + m + ") " + nrWhere(s) + facet + " TIMESERIES");
            }
        }
    }

    private static void nrFamily(ServiceSignals s, Doc d, Timing timing, String title, String metric, String facet, String failed) {
        if (s.flows().stream().noneMatch(f -> f.timing() == timing)) {
            return;
        }
        d.text("\n### " + title + " (`" + metric + "`)\n");
        d.query("sql", "Calls per minute, failed share (%), average and p95 duration, per " + facet + ":",
                "FROM Metric SELECT rate(count(" + metric + "), 1 minute), percentage(count(" + metric + "), WHERE " + failed
                        + ") AS 'failed %', average(" + metric + "), percentile(" + metric + ", 95) " + nrWhere(s) + " FACET "
                        + facet + " SINCE 1 hour ago");
        d.query("sql", "Calls per minute over time:", "FROM Metric SELECT rate(count(" + metric + "), 1 minute) " + nrWhere(s)
                + " FACET " + facet + " TIMESERIES");
    }

    // ---- Splunk ------------------------------------------------------------------------------------------

    /** A field in a search: dotted names in double quotes. */
    static String spl(String field) {
        return field.matches("[A-Za-z_][A-Za-z0-9_]*") ? field : "\"" + field + "\"";
    }

    private static void splunk(ServiceSignals s, Policy.Splunk cfg, Doc d) {
        LogFields f = LogFields.of(s.logFormat());
        String base = "index=" + cfg.index() + (cfg.sourcetype().isEmpty() ? "" : " sourcetype=" + cfg.sourcetype())
                + " " + spl(f.service()) + "=\"" + s.service() + "\"";
        String level = spl(f.level());
        String logger = spl(f.logger());
        String message = spl(f.message());
        String trace = spl(f.mdcPrefix() + "traceId");
        d.text("\n## Splunk (SPL)\n\nLogs as Spring Boot writes them in the `" + s.logFormat() + "` format, read as JSON "
                + "(sourcetype `_json`, or `KV_MODE=json`; otherwise add `| spath` after the base search). The format comes "
                + "from logging.structuredFormat in logast.yaml, else Spring's logging.structured.format.console, else the "
                + "encoder the service uses; if the fields below are not in your logs, set logging.structuredFormat.\n");
        d.query("spl", "Base search: every line of the service.", base);

        d.text("\n### Errors\n");
        d.query("spl", "Errors and warnings over time:", base + " (" + level + "=ERROR OR " + level + "=WARN)\n| timechart span=5m count by " + level);
        d.query("spl", "The most frequent errors:", base + " " + level + "=ERROR\n| stats count latest(_time) as last by " + logger
                + ", " + message + "\n| sort - count");
        if (f.errorType() != null) {
            d.query("spl", "Exceptions by type:", base + " " + spl(f.errorType()) + "=*\n| stats count by " + spl(f.errorType())
                    + ", " + logger + "\n| sort - count");
        } else {
            d.query("spl", "Exceptions by type (the first line of the stack trace):", base + " " + spl(f.stack()) + "=*\n| rex field="
                    + spl(f.stack()) + " \"^(?<exception>[\\w.$]+)\"\n| stats count by exception, " + logger + "\n| sort - count");
        }

        d.text("\n### One request, run or business id\n");
        d.query("spl", "Everything one trace logged, in order (the trace id from an error response or another line):",
                base + " " + trace + "=\"<trace id>\"\n| sort _time\n| table _time " + level + " " + logger + " " + message);
        for (String key : s.mdcKeys()) {
            String field = spl(f.mdcPrefix() + key);
            d.query("spl", "Everything logged for one `" + key + "`, across requests and jobs:",
                    base + " " + field + "=\"<" + key + ">\"\n| sort _time\n| table _time " + trace + " " + level + " " + message);
        }
        for (String key : s.mdcKeys()) {
            String field = spl(f.mdcPrefix() + key);
            d.query("spl", "The `" + key + "` values with the most errors:", base + " " + level + "=ERROR " + field
                    + "=*\n| stats count dc(" + trace + ") as traces by " + field + "\n| sort - count\n| head 20");
        }

        d.text("\n### Log volume\n");
        d.query("spl", "The loggers that write the most (what LOG_VOLUME estimates, from real traffic):",
                base + "\n| stats count by " + logger + ", " + level + "\n| sort - count\n| head 20");
    }

    private static String flowsOf(ServiceSignals s, String name) {
        List<String> eps = s.flows().stream().filter(f -> f.timing() == Timing.OBSERVATION && f.metric().equals(name))
                .map(f -> f.kind() + " " + f.label()).distinct().toList();
        return eps.isEmpty() ? "" : " (" + String.join("; ", eps) + ")";
    }

    private static String outcomesOf(Meter m) {
        return m.outcomes().isEmpty() ? "" : " (outcome: " + String.join(", ", m.outcomes()) + ")";
    }

    private static String cell(String s) {
        return s.replace("|", "\\|");
    }
}
