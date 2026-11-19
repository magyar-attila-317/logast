package dev.logast.cli;

import dev.logast.Analysis;
import dev.logast.LogastDir;
import dev.logast.orders.Ansi;
import dev.logast.orders.OrderJson;
import dev.logast.queries.QueryWriter;
import dev.logast.queries.ServiceSignals;
import dev.logast.rules.LifecyclePlan;
import dev.logast.rules.MetricsMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * #125: the reports every scan and apply writes to .logast/reports, from the analysis the run already made, and that
 * {@code logast explain metrics|queries|lifecycle} prints: one rendering each, so the file and the command agree.
 */
final class Reports {
    private Reports() {
    }

    /** What the service measures and what it does not; format map (default) | json | table (meters only). */
    static String metrics(Analysis a, String format) throws IOException {
        if (format.equals("table")) {
            return dev.logast.metrics.MeterCatalog.table(dev.logast.metrics.MeterCatalog.of(a));
        }
        MetricsMap.Map map = MetricsMap.of(a);
        return format.equals("json") ? OrderJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(map) + "\n"
                : MetricsMap.text(map);
    }

    /** PromQL, NRQL and Splunk SPL per entrypoint, business outcome and MDC key (backends: queries: in logast.yaml). */
    static QueryWriter.Result queries(Analysis a) {
        return QueryWriter.markdown(ServiceSignals.of(a), a.policy.queries());
    }

    /** Every flow's lifecycle lines by kind, then implementation families that log unevenly. */
    static String lifecycle(Analysis a, LifecyclePlan plan) {
        StringBuilder out = new StringBuilder();
        Map<String, List<LifecyclePlan.Flow>> byKind = new TreeMap<>();
        a.entrypoints.forEach(ep -> {
            var f = plan.flow(ep);
            byKind.computeIfAbsent(f.kind().name().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(f);
        });
        for (var e : byKind.entrySet()) {
            long open = e.getValue().stream().filter(f -> !f.missing().isEmpty()).count();
            out.append(Ansi.bold(e.getKey() + " (" + e.getValue().size() + ")")).append(Ansi.dim(open == 0 ? "" : "   " + open + " missing lines")).append('\n');
            for (var f : e.getValue()) {
                out.append("  ").append(f.label()).append(Ansi.dim("  " + f.location())).append('\n');
                out.append("    start: ").append(f.started() ? "present" : "none").append(Ansi.dim(" (" + f.startOwner() + ")"))
                        .append("   outcome: ").append(f.outcome() ? "present" : "none").append(Ansi.dim(" (" + f.outcomeOwner() + ")"))
                        .append(f.missing().isEmpty() ? "" : Ansi.yellow("   adds: " + String.join(", ", f.missing()))).append('\n');
            }
        }
        var families = plan.families();
        if (!families.isEmpty()) {
            out.append(Ansi.bold("implementation families (" + families.size() + ")")).append('\n');
            for (var fam : families) {
                out.append("  ").append(a.index.method(fam.base()).label()).append('\n');
                fam.has().forEach((impl, has) -> out.append("    ").append(a.index.method(impl).label()).append(": ")
                        .append(has.isEmpty() ? "no lifecycle lines" : String.join(", ", has.keySet()))
                        .append(fam.missing().containsKey(impl) ? Ansi.yellow("   adds: " + String.join(", ", fam.missing().get(impl))) : "")
                        .append('\n'));
            }
        }
        return out.toString();
    }

    /**
     * Writes metrics.md, queries.md and lifecycle.txt to {@code <root>/.logast/reports} from {@code a} (analysed with
     * {@code plan} by the run that calls this): the same text the explain topics print when not in a terminal.
     */
    static void write(Path root, Analysis a, LifecyclePlan plan) throws IOException {
        writeTo(root.resolve(LogastDir.DIR), a, plan);
    }

    /** {@link #write} into {@code out}, a folder laid out like .logast (#132: a fleet's added member). */
    static void writeTo(Path out, Analysis a, LifecyclePlan plan) throws IOException {
        long t0 = System.nanoTime();
        boolean colours = Ansi.enabled();
        Ansi.enable(false);   // a file: the text a pipe gets ("WARNING:", not a coloured "!")
        try {
            Files.createDirectories(LogastDir.in(out, LogastDir.REPORTS));
            Files.writeString(LogastDir.in(out, LogastDir.METRICS_REPORT), metrics(a, "map"));
            Files.writeString(LogastDir.in(out, LogastDir.QUERIES), queries(a).text());
            if (plan != null) {
                Files.writeString(LogastDir.in(out, LogastDir.LIFECYCLE), lifecycle(a, plan));
            }
        } finally {
            Ansi.enable(colours);
        }
        if (System.getenv("LOGAST_TIMING") != null) {
            System.err.printf("timing: reports %d ms%n", (System.nanoTime() - t0) / 1_000_000);
        }
    }

    /** The one line after a run's footer that says where the reports are (#125). */
    static void printWritten() {
        Main.printBlocks(List.of(Ansi.dim("reports: .logast/reports/ metrics.md queries.md lifecycle.txt "
                + "(logast explain metrics|queries|lifecycle prints them)")));
    }
}
