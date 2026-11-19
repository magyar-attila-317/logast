package dev.logast.cli;

import dev.logast.rules.OrderBuilder;
import dev.logast.rules.Rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** #126: apply's summary for a person: what the run did by kind, in plain words, and its one progress line. */
final class ApplySummary {
    private ApplySummary() {
    }

    /** Lines by kind at most; the rest goes into one "other changes" line. */
    static final int MAX_KINDS = 6;

    private static final Map<Rules.Step, String> STEP_WORDS = Map.of(
            Rules.Step.SETUP, "setup", Rules.Step.STRUCTURE, "MDC and trace context", Rules.Step.LINES, "missing log lines",
            Rules.Step.MDC, "MDC keys", Rules.Step.MESSAGES, "log messages", Rules.Step.EXCEPTIONS, "exceptions",
            Rules.Step.LEVELS, "log levels", Rules.Step.REPORTS, "metrics and the rest");

    /**
     * The live progress line for an {@link dev.logast.apply.Applier} progress message: "migrating… step 3/8: missing log
     * lines" for a step's round, else "migrating… " and the message.
     */
    static String progress(String message) {
        String text = message.startsWith("~") ? message.substring(1) : message;
        Rules.Step[] steps = Rules.Step.values();
        for (Rules.Step s : steps) {
            if (text.startsWith(s.name().toLowerCase(Locale.ROOT) + ", round ")) {
                return "migrating… step " + (s.ordinal() + 1) + "/" + steps.length + ": " + STEP_WORDS.get(s);
            }
        }
        int paren = text.indexOf(" (");
        return "migrating… " + (paren > 0 ? text.substring(0, paren) : text);
    }

    /**
     * What the applied orders did, by kind ({@code applied}: order ids, whose prefix names the rule), largest first, at
     * most {@link #MAX_KINDS} lines; with the support classes copied into {@code supportPackage} (vendor mode, null
     * otherwise) counted among {@code files}.
     */
    static List<String> kinds(List<String> applied, List<String> files, String supportPackage) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String id : applied) {
            int dash = id.indexOf('-');
            String rule = dash > 0 ? OrderBuilder.ruleOfPrefix(id.substring(0, dash)) : null;
            counts.merge(kind(rule), 1, Integer::sum);
        }
        List<String> out = new ArrayList<>();
        List<Map.Entry<String, Integer>> sorted = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).toList();
        int other = 0;
        for (int i = 0; i < sorted.size(); i++) {
            var e = sorted.get(i);
            if (i < MAX_KINDS - 1 || sorted.size() == MAX_KINDS) {
                out.add(words(e.getKey(), e.getValue()));
            } else {
                other += e.getValue();
            }
        }
        if (other > 0) {
            out.add(n(other, "other change", "other changes"));
        }
        if (supportPackage != null) {
            String dir = supportPackage.replace('.', '/') + "/";
            long copied = files.stream().filter(f -> f.contains("/" + dir) && f.endsWith(".java")).count();
            if (copied > 0) {
                out.add("support code copied into " + dir.substring(0, dir.length() - 1) + " (" + n(copied, "file", "files") + ")");
            }
        }
        return out;
    }

    private static String kind(String rule) {
        if (rule == null) {
            return "OTHER";
        }
        if (rule.equals(Rules.METRIC_OUTCOME_UNMEASURED)) {
            return "OUTCOMES";
        }
        if (rule.equals(Rules.MISSING_DOMAIN_MDC)) {
            return "DOMAIN_MDC";
        }
        try {
            return Rules.stepOf(rule).name();
        } catch (IllegalStateException e) {
            return "OTHER";
        }
    }

    private static String words(String kind, long count) {
        return switch (kind) {
            case "OUTCOMES" -> n(count, "outcome counted (a metric next to its log line)", "outcomes counted (a metric next to their log line)");
            case "DOMAIN_MDC" -> n(count, "business id put into MDC", "business ids put into MDC");
            case "SETUP" -> n(count, "setup change (dependencies, configuration, tracing)", "setup changes (dependencies, configuration, tracing)");
            case "STRUCTURE" -> n(count, "fix to how MDC and the trace context are carried", "fixes to how MDC and the trace context are carried");
            case "LINES" -> n(count, "log line added where something happened silently", "log lines added where something happened silently");
            case "MDC" -> n(count, "MDC key tidied", "MDC keys tidied");
            case "MESSAGES" -> n(count, "log line rewritten in the standard format", "log lines rewritten in the standard format");
            case "EXCEPTIONS" -> n(count, "exception log fixed", "exception logs fixed");
            case "LEVELS" -> n(count, "log level adjusted", "log levels adjusted");
            case "REPORTS" -> n(count, "metric, error-handling or audit fix", "metric, error-handling and audit fixes");
            default -> n(count, "other change", "other changes");
        };
    }

    private static String n(long count, String one, String many) {
        return count + " " + (count == 1 ? one : many);
    }
}
