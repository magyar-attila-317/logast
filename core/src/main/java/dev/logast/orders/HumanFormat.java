package dev.logast.orders;

import dev.logast.Analysis;
import dev.logast.rules.RuleLabels;
import dev.logast.rules.Rules;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The scan for a person at a terminal (#107): what logast fixes and what needs a message as counts, the decisions
 * grouped by severity and rule with one line per finding. {@link CompactFormat} stays the full listing (for an LLM,
 * and for one rule: {@code --rule X}).
 */
public final class HumanFormat {
    /** Findings listed per rule before "+N more". */
    static final int PER_RULE = 4;

    private HumanFormat() {
    }

    /**
     * @param appPackage the application's base package (paths are shown below it), or null
     * @param width      the terminal width lines are cut to
     */
    public static String render(ScanResult r, List<WorkOrder> orders, String appPackage, int width) {
        StringBuilder sb = new StringBuilder();
        Analysis.Stats s = r.stats();
        sb.append(Ansi.bold("logast")).append(Ansi.dim(" · ")).append(s.files()).append(" files").append(Ansi.dim(" · "))
                .append(s.entrypoints()).append(s.entrypoints() == 1 ? " entrypoint" : " entrypoints");
        if (r.coverage() != null && r.coverage().total() > 0) {
            double p = r.coverage().percent();
            sb.append(Ansi.dim(" · ")).append("log coverage ").append(p >= 90 ? Ansi.green(p + "%") : p >= 60 ? Ansi.yellow(p + "%") : Ansi.red(p + "%"));
        }
        sb.append('\n');
        if (!s.coverageComplete()) {
            sb.append(Ansi.warn(s.files() + " of " + s.discoveredFiles() + " source files analysed (`--format compact` names "
                    + "the others): findings there are missing")).append('\n');
        }
        if (s.noClasspath()) {
            sb.append(Ansi.warn("no classpath: run `logast classpath .`, or calls through libraries stay unresolved")).append('\n');
        }
        if (s.unresolvedCalls() > 0) {
            sb.append(Ansi.warn(s.unresolvedCalls() + " calls did not resolve: paths through them may be missing "
                    + "(`--format compact` lists them)")).append('\n');
        }

        List<WorkOrder> auto = orders.stream().filter(WorkOrder::mechanical).toList();
        List<WorkOrder> text = orders.stream().filter(o -> !o.reportOnly() && !o.slots().isEmpty()).toList();
        // an uncounted outcome is an entry of metrics.json (count, tag or skip), not a decision to read about
        List<WorkOrder> outcomes = orders.stream().filter(o -> o.reportOnly() && o.rule().equals(Rules.METRIC_OUTCOME_UNMEASURED)).toList();
        List<WorkOrder> decisions = orders.stream().filter(o -> o.reportOnly() && !o.rule().equals(Rules.METRIC_OUTCOME_UNMEASURED)).toList();
        sb.append('\n').append(Ansi.bold(orders.size() + " findings")).append(": ")
                .append(Ansi.green(auto.size() + " logast fixes")).append(", ")
                .append(Ansi.cyan(text.size() + " need a message")).append(", ")
                .append(outcomes.isEmpty() ? "" : Ansi.cyan(outcomes.size() + " outcomes to count or skip") + ", ")
                .append(Ansi.magenta(decisions.size() + (decisions.size() == 1 ? " needs your decision" : " need your decision")))
                .append('\n');
        if (!auto.isEmpty()) {
            sb.append("  ").append(Ansi.green("logast fixes")).append("     ").append(cut(byLabel(auto), width - 19)).append('\n');
        }
        if (!text.isEmpty()) {
            String where = "write in .logast/fills.json or `logast review .`: ";
            sb.append("  ").append(Ansi.cyan("messages")).append("         ").append(Ansi.dim(where))
                    .append(cut(byLabel(text), width - 19 - where.length())).append('\n');
        }
        if (!outcomes.isEmpty()) {
            sb.append("  ").append(Ansi.cyan("outcomes")).append("         ").append(Ansi.dim("decide in .logast/metrics.json "
                    + "(`logast metrics .` has the context): ")).append(outcomes.size()).append(" logged but not counted").append('\n');
        }

        if (!decisions.isEmpty()) {
            sb.append('\n').append(Ansi.bold("Your decisions")).append(Ansi.dim(" (most important first; → what to do)")).append('\n');
            Map<String, Map<String, List<WorkOrder>>> bySeverity = new TreeMap<>(
                    Comparator.comparingInt((String sev) -> -Rules.rank(sev)));
            for (WorkOrder o : decisions) {
                bySeverity.computeIfAbsent(o.severity(), k -> new LinkedHashMap<>())
                        .computeIfAbsent(o.rule(), k -> new ArrayList<>()).add(o);
            }
            for (var sev : bySeverity.entrySet()) {
                sb.append("  ").append(Ansi.severity(sev.getKey())).append('\n');
                List<Map.Entry<String, List<WorkOrder>>> rules = new ArrayList<>(sev.getValue().entrySet());
                rules.sort(Comparator.comparingInt((Map.Entry<String, List<WorkOrder>> e) -> -e.getValue().size())
                        .thenComparing(Map.Entry::getKey));
                for (var rule : rules) {
                    List<WorkOrder> os = rule.getValue();
                    os.sort(Comparator.comparing(WorkOrder::file).thenComparingInt(WorkOrder::line));
                    sb.append("    ").append(Ansi.bold(RuleLabels.of(rule.getKey())))
                            .append(os.size() > 1 ? " (" + os.size() + ")" : "").append("  ").append(Ansi.dim(rule.getKey())).append('\n');
                    // what to do: the first instruction of the finding's note; one line for the rule when every finding says the same
                    List<String> actions = os.stream().map(HumanFormat::action).filter(Objects::nonNull).distinct().toList();
                    if (!actions.isEmpty()) {
                        String eg = actions.size() > 1 ? "e.g. " : "";   // findings that need different fixes: the first one's
                        sb.append("      ").append(Ansi.green("→ ")).append(Ansi.dim(eg)).append(cut(actions.getFirst(), width - 8 - eg.length()))
                                .append('\n');
                    }
                    // the same place and text twice (two calls on one line) is one line with ×2
                    Map<String, Integer> lines = new LinkedHashMap<>();
                    for (WorkOrder o : os) {
                        String key = location(o, appPackage) + "\u0000" + (o.title() == null ? "" : o.title());
                        lines.merge(key, 1, Integer::sum);
                    }
                    List<String> keys = new ArrayList<>(lines.keySet());
                    int locWidth = Math.min(48, keys.stream().limit(PER_RULE).mapToInt(k -> k.indexOf('\u0000')).max().orElse(0));
                    for (int i = 0; i < Math.min(keys.size(), PER_RULE); i++) {
                        String loc = keys.get(i).substring(0, keys.get(i).indexOf('\u0000'));
                        String title = keys.get(i).substring(keys.get(i).indexOf('\u0000') + 1);
                        int times = lines.get(keys.get(i));
                        String suffix = times > 1 ? " ×" + times : "";
                        int room = width - 6 - Math.max(locWidth, loc.length()) - 2 - suffix.length();
                        sb.append("      ").append(Ansi.dim(pad(loc, locWidth))).append("  ").append(cut(title, room)).append(suffix).append('\n');
                    }
                    if (keys.size() > PER_RULE) {
                        sb.append("      ").append(Ansi.dim("+" + (keys.size() - PER_RULE) + " more")).append('\n');
                    }
                }
            }
            String example = decisions.stream().filter(o -> Rules.rank(o.severity()) == Rules.rank(bySeverity.keySet().iterator().next()))
                    .map(WorkOrder::rule).findFirst().orElse(decisions.getFirst().rule());
            sb.append('\n').append(Ansi.dim("what to decide, and when it is done: logast scan . --rule " + example
                    + "   ·   everything in full (for an LLM): --format compact")).append('\n');
        }
        return sb.toString();
    }

    /** Sentences of a note that explain rather than instruct ("Not removed: …", "Report only: …"). */
    private static final java.util.regex.Pattern EXPLAINS = java.util.regex.Pattern.compile("(?i)^(not removed|not applied|report only|logast )");

    /** The first instruction in the finding's note ("Pass a context-propagating executor: …"), or null. */
    static String action(WorkOrder o) {
        if (o.suggestion() == null || o.suggestion().isBlank()) {
            return null;
        }
        for (String sentence : o.suggestion().replaceAll("\\s+", " ").strip().split("(?<=[.!?])\\s+(?=[A-Z])")) {
            if (!EXPLAINS.matcher(sentence).find()) {
                return sentence;
            }
        }
        return null;
    }

    /** "message not in house format 12, log line loses the exception 3, …" by count, at most five. */
    static String byLabel(List<WorkOrder> orders) {
        Map<String, Long> counts = orders.stream().collect(Collectors.groupingBy(o -> RuleLabels.of(o.rule()), Collectors.counting()));
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry::getKey));
        String shown = sorted.stream().limit(5).map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", "));
        return shown + (sorted.size() > 5 ? ", …" : "");
    }

    /**
     * The file below the application package: {@code src/main/java/com/x/app/jobs/A.java} → {@code jobs/A.java:12};
     * resources lose {@code src/main/resources/}; other files stay as they are.
     */
    static String location(WorkOrder o, String appPackage) {
        String f = o.file();
        String java = "src/main/java/";
        if (f.startsWith(java)) {
            f = f.substring(java.length());
            String pkg = appPackage == null ? null : appPackage.replace('.', '/') + "/";
            if (pkg != null && f.startsWith(pkg)) {
                f = f.substring(pkg.length());
            }
        } else if (f.startsWith("src/main/resources/")) {
            f = f.substring("src/main/resources/".length());
        }
        return f + ":" + o.line();
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }

    static String cut(String s, int room) {
        String one = s.replaceAll("\\s+", " ").strip();
        if (room < 20) {
            room = 20;
        }
        return one.length() <= room ? one : one.substring(0, room - 1) + "…";
    }
}
