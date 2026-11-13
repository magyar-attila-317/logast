package dev.logast;

import dev.logast.apply.FillsFile;
import dev.logast.apply.MetricsFile;
import dev.logast.orders.ScanResult;
import dev.logast.orders.WorkOrder;
import dev.logast.policy.Policy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * What is left for a person after scan/apply, one line per action: the files to fill, the decisions to make, the
 * checks to run. Also creates .logast/traffic.yaml when it is missing, so every file to fill exists.
 */
public final class Todo {
    /** @param action what to do (a file to edit or a command); {@code why} one short reason */
    public record Item(String action, String why) {
    }

    private Todo() {
    }

    static String plural(long n, String word) {
        return n + " " + (n == 1 ? word : word.endsWith("x") ? word + "es" : word + "s");
    }

    /**
     * @param a       the analysis behind {@code result} (entrypoints, for traffic.yaml); null when not available
     * @param applied order ids applied in this run (empty for a scan)
     * @param wrote   files were changed
     */
    public static List<Item> of(Path root, ScanResult result, Policy policy, Analysis a, Collection<String> applied, boolean wrote) {
        List<Item> out = new ArrayList<>();
        List<WorkOrder> orders = result.orders();
        if (result.stats() != null && result.stats().noClasspath()) {
            out.add(new Item("logast classpath .", "logast cannot see library types yet; findings are incomplete"));
        }
        // fixes that wait for a text order are counted with the texts, not as "apply again"
        Set<String> textIds = new HashSet<>();
        orders.stream().filter(o -> !o.slots().isEmpty()).forEach(o -> textIds.add(o.id()));
        long auto = orders.stream().filter(WorkOrder::mechanical).filter(o -> o.dependsOn().stream().noneMatch(textIds::contains)).count();
        if (auto > 0) {
            out.add(new Item("logast apply . --auto --write", plural(auto, "fix") + " need nothing from you"));
        }
        long text = orders.stream().filter(o -> !o.slots().isEmpty()).count();
        if (text > 0) {
            out.add(new Item("write \"MSG\" texts in " + FillsFile.PATH, text + " log lines need wording only a person knows; then apply again"));
        }
        long outcomes = orders.stream().filter(o -> o.rule().equals(MetricsFile.RULE) && o.reportOnly()).count();
        if (outcomes > 0) {
            out.add(new Item("set \"count\" in " + MetricsFile.PATH, outcomes + " logged outcomes are not counted: counter, tag or skip each; then apply again"));
        }
        if (a != null && !a.entrypoints.isEmpty()) {
            Path file = root.resolve(".logast/traffic.yaml");
            Traffic existing = Files.exists(file) ? Traffic.load(root, file) : Traffic.none();
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
                out.add(new Item("fill calls/day in .logast/traffic.yaml (optional)", (labels.size() - known) + " of " + labels.size()
                        + " entrypoints have no rate: log levels and metrics frequency stay cautious there"));
            }
        }
        Map<String, Long> report = new TreeMap<>();
        orders.stream().filter(WorkOrder::reportOnly).filter(o -> !o.rule().equals(MetricsFile.RULE))
                .forEach(o -> report.merge(o.rule(), 1L, Long::sum));
        if (!report.isEmpty()) {
            long n = report.values().stream().mapToLong(Long::longValue).sum();
            out.add(new Item("logast scan . --format summary", n + " findings are decisions for a person ("
                    + String.join(", ", report.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue(), x.getValue()))
                    .limit(3).map(e -> e.getKey() + " " + e.getValue()).toList()) + (report.size() > 3 ? ", …" : "")
                    + "); each says what to decide"));
        }
        if (policy.terms().isEmpty() && policy.vocabulary().isEmpty()) {
            out.add(new Item("logast vocabulary .", "no business terms in logast.yaml yet: MDC keys and wording checks need them"));
        }
        boolean starterAdded = applied.stream().anyMatch(id -> id.startsWith("SUP-"));
        if (starterAdded && !policy.support().vendor()) {
            out.add(new Item("check " + policy.support().coordinates() + " resolves", "the build now depends on it; or set support.mode: vendor in logast.yaml"));
        }
        if (wrote) {
            out.add(new Item("logast verify .", "build and run the tests with log capture: does the result really log what it should?"));
        }
        return out;
    }
}
