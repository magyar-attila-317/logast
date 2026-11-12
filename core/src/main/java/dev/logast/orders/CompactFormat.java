package dev.logast.orders;

import dev.logast.Analysis;
import dev.logast.rules.Rules;
import dev.logast.rules.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/** Token-cheap, LLM-oriented rendering of a scan: a few lines per order, everything needed to fill slots. */
public final class CompactFormat {
    private CompactFormat() {
    }

    public static String render(ScanResult r, List<WorkOrder> orders, List<String> glossaryTerms) {
        StringBuilder sb = new StringBuilder();
        Analysis.Stats s = r.stats();
        sb.append(Ansi.bold("logast")).append(Ansi.dim(" · ")).append(s.files()).append(" files").append(Ansi.dim(" · "))
                .append(s.entrypoints()).append(" entrypoints").append(Ansi.dim(" · "))
                .append(s.reachableMethods()).append('/').append(s.methods()).append(" methods reachable").append(Ansi.dim(" · "))
                .append(s.edges()).append(" call edges\n");
        if (!s.coverageComplete()) {
            sb.append(Ansi.warn("coverage incomplete: " + s.files() + " of " + s.discoveredFiles() + " source files analysed; "
                    + String.join("; ", s.parseErrors().stream().limit(5).toList()) + (s.parseErrors().size() > 5 ? "; …" : ""))).append('\n');
        }
        if (s.noClasspath()) {
            sb.append(Ansi.warn("no classpath (run `logast classpath <root>`); library types are unresolved and call edges may be missing")).append('\n');
        }
        if (s.unresolvedCalls() > 0) {
            sb.append(Ansi.warn(s.unresolvedCalls() + " unresolved calls, e.g. " + s.unresolvedSample())).append('\n');
        }
        if (r.coverage() != null && r.coverage().total() > 0) {
            double p = r.coverage().percent();
            String pct = p >= 90 ? Ansi.green(p + "%") : p >= 60 ? Ansi.yellow(p + "%") : Ansi.red(p + "%");
            sb.append(Ansi.bold("log coverage ")).append(pct).append(Ansi.dim(r.coverage().line().substring(r.coverage().line().indexOf('%') + 1)
                    + "   (places that should log and do)")).append('\n');
        }
        long auto = orders.stream().filter(WorkOrder::mechanical).count();
        long text = orders.stream().filter(o -> !o.slots().isEmpty()).count();
        long report = orders.stream().filter(WorkOrder::reportOnly).count();
        sb.append(Ansi.bold(orders.size() + " orders")).append(": ").append(Ansi.green(auto + " AUTO")).append(", ")
                .append(Ansi.cyan(text + " NEEDS TEXT")).append(", ").append(Ansi.magenta(report + " REPORT-ONLY"))
                .append(Ansi.dim("   (AUTO = logast fixes it · NEEDS TEXT = someone writes one message · REPORT-ONLY = a decision for a person)"))
                .append('\n');
        if (!glossaryTerms.isEmpty()) {
            sb.append(Ansi.dim("glossary terms: " + String.join(", ", glossaryTerms))).append('\n');
        }
        sb.append('\n');
        Path root = Path.of(r.root());
        for (WorkOrder o : orders) {
            String kind = o.reportOnly() ? "REPORT-ONLY" : o.slots().isEmpty() ? "AUTO" : "NEEDS TEXT";
            sb.append(Ansi.bold(o.id())).append("  ").append(Ansi.severity(o.severity())).append("  ").append(Ansi.kind(kind)).append("  ")
                    .append(Ansi.blue(String.join("+", o.fixes()))).append("  ").append(Ansi.dim(o.file() + ":" + o.line()))
                    .append(o.method() == null ? "" : "  " + Ansi.dim(o.method())).append('\n');
            if (o.title() != null) {
                sb.append("  ").append(o.title()).append('\n');
            }
            if (!o.paths().isEmpty()) {
                sb.append(Ansi.dim("  path: " + o.paths().getFirst()
                        + (o.paths().size() > 1 ? "  (+" + (o.paths().size() - 1) + " more)" : ""))).append('\n');
            }
            for (String line : preview(root, o)) {
                sb.append("  ").append(line.startsWith("- ") ? Ansi.red(line) : line.startsWith("+ ") ? Ansi.green(line) : Ansi.dim(line)).append('\n');
            }
            for (var slot : o.slots().entrySet()) {
                sb.append("  ").append(slot.getKey()).append(": ").append(slot.getValue().describe());
                if (slot.getValue().original() != null) {
                    sb.append(" | was: \"").append(slot.getValue().original()).append('"');
                }
                if (!slot.getValue().keys().isEmpty()) {
                    sb.append(" | suffix keys: ").append(slot.getValue().keys());
                }
                if (slot.getValue().pattern() != null) {
                    sb.append(" | must read as '").append(slot.getValue().pattern()).append("'");
                }
                sb.append('\n');
            }
            if (o.context() != null && o.context().argsSlot() != null) {
                sb.append("  mdc: ").append(o.context().mdcInScope()).append("  vars: ")
                        .append(o.context().varsInScope().entrySet().stream().map(e -> e.getKey() + ":" + e.getValue())
                                .collect(Collectors.joining(", "))).append('\n');
            }
            if (!o.locations().isEmpty()) {
                sb.append("  also fixes: ").append(o.locations().stream().map(l -> l.rule() + " " + l.file() + ":" + l.line())
                        .collect(Collectors.joining(", "))).append('\n');
            }
            if (o.suggestion() != null) {
                sb.append(Ansi.dim("  note: " + o.suggestion())).append('\n');
            }
            if (!o.dependsOn().isEmpty()) {
                sb.append("  depends on: ").append(String.join(", ", o.dependsOn())).append('\n');
            }
        }
        if (text > 0) {
            sb.append("\n").append(Ansi.bold("Next")).append(": write the message for each NEEDS TEXT order into .logast/fills.json"
                    + " (\"MSG\": plain text, no {} and no [k=v]; optional \"args\": [\"var\"] adds variables), then:"
                    + " logast apply <root> --auto\n");
        } else if (auto > 0) {
            sb.append("\n").append(Ansi.bold("Next")).append(": logast apply <root> --auto   (preview; add --write to change files)\n");
        }
        return sb.toString();
    }

    public static List<String> previewLines(Path root, WorkOrder o) {
        return preview(root, o);
    }

    /** "- old" / "+ new" lines for the main edit (the one with a slot, else the largest). */
    static List<String> preview(Path root, WorkOrder o) {
        if (o.edits().isEmpty()) {
            return List.of();
        }
        Edit main = o.edits().stream().filter(e -> e.text().contains("{{")).findFirst()
                .or(() -> o.edits().stream().filter(e -> !e.kind().equals("replace")).findFirst())
                .orElse(o.edits().stream().max(Comparator.comparingInt(e -> e.text().length() + (e.end() - e.start()))).orElseThrow());
        List<String> out = new ArrayList<>();
        switch (main.kind()) {
            case "delete" -> out.add("- delete " + main.file());
            case "create" -> out.add("+ create " + main.file());
            default -> {
                String old = original(root, main);
                if (!old.isBlank() && old.lines().count() <= 1) {
                    out.add("- " + Text.oneLine(old, 140));
                } else if (!old.isBlank()) {
                    out.add("- (" + old.lines().count() + " lines replaced)");
                }
                List<String> lines = main.text().lines().filter(l -> !l.isBlank()).toList();
                for (int i = 0; i < Math.min(lines.size(), 3); i++) {
                    out.add("+ " + lines.get(i).strip());
                }
                if (lines.size() > 3) {
                    out.add("+ … (" + lines.size() + " lines)");
                }
            }
        }
        int others = o.edits().size() - 1;
        if (others > 0) {
            out.add("(+" + others + " more edits)");
        }
        return out;
    }

    private static String original(Path root, Edit e) {
        try {
            Path p = root.resolve(e.file());
            if (!Files.exists(p) || e.start() == e.end()) {
                return "";
            }
            String t = Files.readString(p);
            return e.end() <= t.length() ? t.substring(e.start(), e.end()) : "";
        } catch (IOException ex) {
            return "";
        }
    }

    /** Findings per rule: count, worst severity and how they get fixed; then the totals. */
    public static String summary(List<WorkOrder> orders) {
        StringBuilder sb = new StringBuilder();
        Map<String, List<WorkOrder>> byRule = new TreeMap<>();
        orders.forEach(o -> o.fixes().forEach(f -> byRule.computeIfAbsent(f, k -> new ArrayList<>()).add(o)));
        int w = Math.max(26, byRule.keySet().stream().mapToInt(String::length).max().orElse(0) + 2);
        for (var e : byRule.entrySet()) {
            List<WorkOrder> os = e.getValue();
            String worst = os.stream().map(WorkOrder::severity).max(Comparator.comparingInt(Rules::rank)).orElse("low");
            long auto = os.stream().filter(WorkOrder::mechanical).count();
            long text = os.stream().filter(o -> !o.slots().isEmpty()).count();
            long report = os.stream().filter(WorkOrder::reportOnly).count();
            List<String> how = new ArrayList<>();
            if (auto > 0) how.add(Ansi.green(auto + " auto"));
            if (text > 0) how.add(Ansi.cyan(text + " text"));
            if (report > 0) how.add(Ansi.magenta(report + " report"));
            sb.append(String.format("%-" + w + "s %4d", e.getKey(), os.size())).append("  ")
                    .append(Ansi.severity(worst)).append(" ".repeat(Math.max(0, 6 - worst.length()))).append("  ").append(String.join(Ansi.dim(" · "), how)).append('\n');
        }
        Map<String, Long> bySev = orders.stream().collect(Collectors.groupingBy(WorkOrder::severity, Collectors.counting()));
        sb.append("high=").append(bySev.getOrDefault("high", 0L)).append(" medium=").append(bySev.getOrDefault("medium", 0L))
                .append(" low=").append(bySev.getOrDefault("low", 0L)).append('\n');
        return sb.toString();
    }

    public static boolean atLeast(WorkOrder o, String severity) {
        return Rules.rank(o.severity()) >= Rules.rank(severity);
    }
}
