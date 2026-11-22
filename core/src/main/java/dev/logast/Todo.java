package dev.logast;

import dev.logast.apply.FillsFile;
import dev.logast.apply.MetricsFile;
import dev.logast.orders.Ansi;
import dev.logast.orders.ScanResult;
import dev.logast.orders.WorkOrder;
import dev.logast.policy.Policy;
import dev.logast.rules.RuleLabels;
import dev.logast.rules.Rules;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * What is left after scan/apply, grouped by what has to happen next (#101): the fixes logast makes itself, then what
 * waits on a person (wording, metric decisions, other decisions), then the optional checks. Also creates
 * .logast/decisions/traffic.yaml when it is missing and a selected pack uses it, and keeps .logast/decisions/retry.yaml listing the
 * retry usages (resilience pack), so every file to fill exists.
 *
 * @param auto      fixes that need nothing from a person
 * @param text      orders waiting for a message text in messages.yaml
 * @param outcomes  logged outcomes waiting for a count/tag/skip decision in metrics.yaml
 * @param decisions the other report-only findings, per rule
 * @param first     what has to happen before anything else (refresh the classpath), or null
 * @param also      the optional and follow-up checks, one line each
 */
public record Todo(long auto, long text, long outcomes, long retries, Map<String, Long> decisions, Item first, List<Item> also) {
    /** @param action what to do (a file to edit or a command); {@code why} one short reason */
    public record Item(String action, String why) {
    }

    public static final String APPLY = "logast apply .";
    /** #122: the one pointer for everything optional: decide in one place, then the same command again. */
    public static final String REVIEW = "logast review (or edit .logast/decisions/*.yaml), then " + APPLY + " again";

    public static String plural(long n, String word) {
        return n + " " + (n == 1 ? word : word.endsWith("x") ? word + "es" : word + "s");
    }

    /**
     * @param a       the analysis behind {@code result} (entrypoints, for traffic.yaml); null when not available
     * @param applied order ids applied in this run (empty for a scan)
     * @param wrote   files were changed
     */
    public static Todo of(Path root, ScanResult result, Policy policy, Analysis a, Collection<String> applied, boolean wrote) {
        List<WorkOrder> orders = result.orders();
        Item first = result.stats() != null && result.stats().noClasspath()
                ? new Item("logast classpath .", "logast cannot see library types yet; findings are incomplete") : null;
        // fixes that wait for a text order are counted with the texts, not as "apply again"
        Set<String> textIds = new HashSet<>();
        orders.stream().filter(o -> !o.reportOnly() && !o.mechanical()).forEach(o -> textIds.add(o.id()));
        long auto = orders.stream().filter(WorkOrder::mechanical).filter(o -> o.dependsOn().stream().noneMatch(textIds::contains)).count();
        long text = textIds.size();
        long outcomes = orders.stream().filter(o -> o.rule().equals(MetricsFile.RULE) && o.reportOnly()).count();
        // retry candidates are answered in retry.yaml (#109), like outcomes in metrics.yaml: not a decision to read up on
        long retries = orders.stream().filter(o -> o.rule().equals(dev.logast.rules.Rules.RETRY_RECOMMENDED) && o.reportOnly()).count();
        Map<String, Long> decisions = new TreeMap<>();
        orders.stream().filter(WorkOrder::reportOnly).filter(o -> !o.rule().equals(MetricsFile.RULE)
                        && !o.rule().equals(dev.logast.rules.Rules.RETRY_RECOMMENDED))
                .forEach(o -> decisions.merge(o.rule(), 1L, Long::sum));
        List<Item> also = new ArrayList<>();
        // the rates set log levels (logging) and metrics frequency: other packs alone have no use for them (#88)
        if (a != null && !a.entrypoints.isEmpty() && (policy.packSelected("logging") || policy.packSelected("metrics"))) {
            Path file = root.resolve(LogastDir.TRAFFIC);
            Traffic existing = (Files.exists(file) ? Traffic.load(root, file) : Traffic.none()).withSchedules(a.traffic.schedules());
            if (!Files.exists(file)) {
                try {
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, Traffic.template(a.entrypoints, existing));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            Set<String> labels = new TreeSet<>();
            a.entrypoints.forEach(ep -> labels.add(ep.label()));
            long known = labels.stream().filter(l -> existing.perDay(l) != null).count();
            if (known < labels.size()) {
                also.add(new Item("fill calls/day in .logast/decisions/traffic.yaml (optional)", (labels.size() - known) + " of " + labels.size()
                        + " entrypoints have no rate: log levels and metrics frequency stay cautious there"));
            }
        }
        // retry usages: shared settings by default, settings of their own where the person marks it (#104)
        if (a != null && (dev.logast.rules.Rules.enabled(policy, dev.logast.rules.Rules.RETRY_SETTINGS_HARDCODED)
                || dev.logast.rules.Rules.enabled(policy, dev.logast.rules.Rules.RETRY_RECOMMENDED))
                && dev.logast.rules.RetrySettingsRules.writeWorkFile(root, a, orders) && retries == 0) {
            also.add(new Item("mark retry usages that need settings of their own in " + dev.logast.rules.RetrySettingsRules.WORK_FILE
                    + " (optional)", "every @Retryable reads the shared resilience.retry.* settings unless marked specific there"));
        }
        // #122: no "logast vocabulary ." hint here: apply's setup harvests the vocabulary; extending it is a platform
        // task (docs/platform.md), not a step of the service path
        boolean starterAdded = applied.stream().anyMatch(id -> id.startsWith("SUP-"));
        // #127: the classpath run already resolved it with the build's own repositories
        if (starterAdded && !policy.support().vendor()
                && !Boolean.TRUE.equals(dev.logast.policy.SupportResolvable.read(root, policy.support().version()))) {
            also.add(new Item("check " + policy.support().coordinates() + " resolves", "the build now depends on it; or set support.mode: vendor in logast.yaml"));
        }
        if (wrote) {
            also.add(new Item("logast verify .", "build and run the tests with log capture: does the result really log what it should?"));
        }
        return new Todo(auto, text, outcomes, retries, decisions, first, also);
    }

    /** Nothing to apply and nothing waits on a person. */
    public boolean done() {
        return auto == 0 && text == 0 && outcomes == 0 && retries == 0 && decisions.isEmpty();
    }

    /**
     * How a run renders this: the command that comes next, and where the decisions are listed.
     *
     * @param next          the command that applies the pending fixes, with why; null: {@link #APPLY} when fixes are pending
     * @param decisionsHint where the decisions are listed ("logast scan .", "listed above")
     * @param fills         the messages.yaml state after this run, or null when unknown
     * @param metrics       the metrics.yaml state after this run, or null when unknown
     */
    public record View(Item next, String decisionsHint, FillsFile.Update fills, FillsFile.Update metrics) {
    }

    /**
     * The lines a person reads (#101): first what to run, then what waits on them, grouped by what they do, then the
     * state-aware "then run". Empty lines separate the blocks; nothing is printed for an empty block.
     */
    public List<String> lines(View v) {
        List<String> out = new ArrayList<>();
        if (first != null) {
            out.add(Ansi.bold("First: ") + Ansi.cyan(first.action()) + Ansi.dim("   " + first.why()));
        }
        Item next = v.next() != null ? v.next() : auto > 0
                ? new Item(APPLY, plural(auto, "fix") + " logast makes itself (--dry-run: a preview)") : null;
        if (next != null) {
            out.add(Ansi.bold("Next: ") + Ansi.cyan(next.action()) + Ansi.dim("   " + next.why()));
        }
        long decided = decisions.values().stream().mapToLong(Long::longValue).sum();
        if (text == 0 && outcomes == 0 && retries == 0 && decided == 0) {
            if (next == null && first == null) {
                out.add(Ansi.green("Nothing left: no fixes pending and nothing waits on you."));
            }
            return out;
        }
        if (!out.isEmpty()) {
            out.add("");
        }
        long optional = text + outcomes + retries + decided;
        out.add(Ansi.bold("Optional (" + optional + ")") + Ansi.dim((next != null ? " — the counts change with the apply" : "")
                + " — left as is, each keeps its default:"));
        List<String[]> rows = new ArrayList<>();
        if (text > 0) {
            String written = v.fills() != null && v.fills().filled() > 0 ? "   (" + v.fills().filled() + " written)" : "";
            rows.add(new String[]{String.valueOf(text), (text == 1 ? "log line" : "log lines") + ": a text of your own",
                    FillsFile.PATH + "   (blank: the current text stays)" + written});
        }
        if (outcomes > 0) {
            String state = v.metrics() == null ? "" : (v.metrics().filled() > 0 ? "   (" + v.metrics().filled() + " decided)" : "")
                    + (v.metrics().problems() > 0 ? "   (" + v.metrics().problems() + " cannot be written: see \"_problem\")" : "");
            rows.add(new String[]{String.valueOf(outcomes), (outcomes == 1 ? "outcome" : "outcomes") + ": count, tag or skip",
                    MetricsFile.PATH + "   (blank: no metric)" + state});
        }
        if (retries > 0) {
            rows.add(new String[]{String.valueOf(retries), (retries == 1 ? "call" : "calls") + " to retry: yes or no",
                    dev.logast.rules.RetrySettingsRules.WORK_FILE + "   (blank: no retry)"});
        }
        if (decided > 0) {
            rows.add(new String[]{String.valueOf(decided), decided == 1 ? "decision" : "decisions",
                    v.decisionsHint() + "   (each says what to decide)"});
        }
        int w = rows.stream().mapToInt(r -> r[1].length()).max().orElse(0);
        int nw = rows.stream().mapToInt(r -> r[0].length()).max().orElse(0);
        String indent = " ".repeat(2 + nw + 1);
        for (String[] r : rows) {
            out.add("  " + Ansi.bold(String.format("%" + nw + "s", r[0])) + " " + String.format("%-" + w + "s", r[1]) + "  → " + Ansi.cyan(r[2]));
            if (r[1].startsWith("decision") && decisions.size() > 1) {
                out.add(Ansi.dim(indent + "mostly: " + String.join(", ", decisions.entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                        .limit(3).map(e -> RuleLabels.of(e.getKey()) + " " + e.getValue()).toList())
                        + (decisions.size() > 3 ? ", …" : "")));
            } else if (r[1].startsWith("decision")) {
                out.add(Ansi.dim(indent + RuleLabels.of(decisions.keySet().iterator().next())));
            }
        }
        if (text > 0 || outcomes > 0 || retries > 0) {
            out.add("  to decide: " + Ansi.cyan(REVIEW) + Ansi.dim("   (on this code: don't revert it)"));
        } else {
            out.add(Ansi.dim("  logast applies nothing for these: change the code, and the next scan drops what is done"));
        }
        return out;
    }

    /**
     * #126: apply's view of what is left, for a person: what to run next (a dry run), then the optional work in plain
     * sentences with one pointer each: what {@code logast review} decides, and what logast can't fix itself.
     */
    public List<String> worthALook(View v) {
        List<String> out = new ArrayList<>();
        if (first != null) {
            out.add(Ansi.bold("First: ") + Ansi.cyan(first.action()) + Ansi.dim("   " + first.why()));
        }
        if (v.next() != null) {
            out.add(Ansi.bold("Next: ") + Ansi.cyan(v.next().action()) + Ansi.dim("   " + v.next().why()));
        }
        long decided = decisions.values().stream().mapToLong(Long::longValue).sum();
        if (text == 0 && outcomes == 0 && retries == 0 && decided == 0) {
            return out;
        }
        if (!out.isEmpty()) {
            out.add("");
        }
        out.add(Ansi.bold("Worth a look (optional)") + Ansi.dim(" — the service works without these:"));
        List<String> review = new ArrayList<>();
        if (text > 0) {
            review.add(text + (text == 1 ? " log line could say more" : " log lines could say more"));
        }
        if (outcomes > 0) {
            review.add(outcomes + (outcomes == 1 ? " outcome could be counted" : " outcomes could be counted"));
        }
        if (retries > 0) {
            review.add(retries + (retries == 1 ? " call could retry" : " calls could retry"));
        }
        if (!review.isEmpty()) {
            String problems = v.metrics() != null && v.metrics().problems() > 0
                    ? Ansi.dim("   (" + v.metrics().problems() + " in metrics.yaml cannot be written: see \"_problem\")") : "";
            out.add("  " + String.join(", ", review) + " → " + Ansi.cyan("logast review") + problems);
        }
        if (decided > 0) {
            List<String> eg = decisions.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .limit(3).map(e -> RuleLabels.of(e.getKey()) + " (" + e.getValue() + ")").toList();
            out.add("  " + decided + (decided == 1 ? " thing" : " things") + " logast can't fix itself, e.g. " + String.join(", ", eg)
                    + " → " + Ansi.cyan(v.decisionsHint()));
        }
        return out;
    }

    /** #126: the follow-up checks, one short "Also" line each. */
    public static List<String> alsoShort(List<Item> items) {
        List<String> out = new ArrayList<>();
        items.forEach(i -> out.add(Ansi.bold("Also: ") + Ansi.cyan(i.action()) + Ansi.dim("   " + i.why())));
        return out;
    }

    /** "Also": the follow-up and optional checks, with extra items of the caller's first (git diff, rejected fixes). */
    public static List<String> alsoLines(List<Item> items) {
        List<String> out = new ArrayList<>();
        if (items.isEmpty()) {
            return out;
        }
        out.add(Ansi.bold("Also:"));
        int w = items.stream().mapToInt(i -> i.action().length()).max().orElse(0) + 3;
        items.forEach(i -> out.add("  " + Ansi.cyan(String.format("%-" + w + "s", i.action())) + Ansi.dim(i.why())));
        return out;
    }

    /** Short location: the first directory, "…" and the file name ({@code src/…/X.java:31}). */
    static String shortLocation(String file, int line) {
        String[] parts = file.split("/");
        String f = parts.length > 2 ? parts[0] + "/…/" + parts[parts.length - 1] : file;
        return f + ":" + line;
    }

    /**
     * "Fix first": the person's orders (text or decision) of the worst severity among them, up to {@code max}, with the
     * other severities counted (#101). Empty when nothing waits on a person.
     */
    public static List<String> fixFirst(List<WorkOrder> orders, int max) {
        List<WorkOrder> person = orders.stream().filter(o -> !o.mechanical()).toList();
        if (person.isEmpty()) {
            return List.of();
        }
        String worst = person.stream().map(WorkOrder::severity).max(Comparator.comparingInt(Rules::rank)).orElseThrow();
        List<WorkOrder> top = person.stream().filter(o -> o.severity().equals(worst)).toList();
        List<String> out = new ArrayList<>();
        out.add(Ansi.bold("Fix first (" + worst + "):"));
        int lw = top.stream().limit(max).mapToInt(o -> RuleLabels.of(o.rule()).length()).max().orElse(0);
        int fw = top.stream().limit(max).mapToInt(o -> shortLocation(o.file(), o.line()).length()).max().orElse(0);
        top.stream().limit(max).forEach(o -> out.add("  " + String.format("%-" + lw + "s", RuleLabels.of(o.rule())) + "   "
                + String.format("%-" + fw + "s", shortLocation(o.file(), o.line())) + "   " + Ansi.dim(o.rule())));
        List<String> rest = new ArrayList<>();
        if (top.size() > max) {
            rest.add((top.size() - max) + " more " + worst);
        }
        for (String sev : List.of("high", "medium", "low")) {
            long n = person.stream().filter(o -> o.severity().equals(sev)).count();
            if (!sev.equals(worst) && n > 0) {
                rest.add(n + " " + sev);
            }
        }
        if (!rest.isEmpty()) {
            out.add(Ansi.dim("  (" + String.join(", ", rest) + ": logast scan .)"));
        }
        return out;
    }
}
