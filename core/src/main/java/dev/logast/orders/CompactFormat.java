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
        sb.append("logast: ").append(s.files()).append(" files, ").append(s.entrypoints()).append(" entrypoints, ")
                .append(s.reachableMethods()).append('/').append(s.methods()).append(" methods reachable, ")
                .append(s.edges()).append(" call edges\n");
        if (!s.coverageComplete()) {
            sb.append("WARNING: coverage incomplete: ").append(s.files()).append(" of ").append(s.discoveredFiles())
                    .append(" source files analysed; ").append(String.join("; ", s.parseErrors().stream().limit(5).toList()))
                    .append(s.parseErrors().size() > 5 ? "; …" : "").append('\n');
        }
        if (s.noClasspath()) {
            sb.append("WARNING: no classpath (run `logast classpath <root>`); library types are unresolved and call edges may be missing\n");
        }
        if (s.unresolvedCalls() > 0) {
            sb.append("WARNING: ").append(s.unresolvedCalls()).append(" unresolved calls, e.g. ").append(s.unresolvedSample()).append('\n');
        }
        long auto = orders.stream().filter(WorkOrder::mechanical).count();
        long text = orders.stream().filter(o -> !o.slots().isEmpty()).count();
        long report = orders.stream().filter(WorkOrder::reportOnly).count();
        sb.append(orders.size()).append(" orders: ").append(auto).append(" AUTO, ").append(text)
                .append(" NEEDS TEXT, ").append(report).append(" REPORT-ONLY\n");
        if (!glossaryTerms.isEmpty()) {
            sb.append("glossary terms: ").append(String.join(", ", glossaryTerms)).append('\n');
        }
        sb.append('\n');
        Path root = Path.of(r.root());
        for (WorkOrder o : orders) {
            String kind = o.reportOnly() ? "REPORT-ONLY" : o.slots().isEmpty() ? "AUTO" : "NEEDS TEXT";
            sb.append(o.id()).append("  ").append(o.severity()).append("  ").append(kind).append("  ")
                    .append(String.join("+", o.fixes())).append("  ").append(o.file()).append(':').append(o.line())
                    .append(o.method() == null ? "" : "  " + o.method()).append('\n');
            if (o.title() != null) {
                sb.append("  ").append(o.title()).append('\n');
            }
            if (!o.paths().isEmpty()) {
                sb.append("  path: ").append(o.paths().getFirst())
                        .append(o.paths().size() > 1 ? "  (+" + (o.paths().size() - 1) + " more)" : "").append('\n');
            }
            for (String line : preview(root, o)) {
                sb.append("  ").append(line).append('\n');
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
                sb.append("  note: ").append(o.suggestion()).append('\n');
            }
            if (!o.dependsOn().isEmpty()) {
                sb.append("  depends on: ").append(String.join(", ", o.dependsOn())).append('\n');
            }
        }
        if (text > 0) {
            sb.append("\nNext: write fills.json {\"<id>\": {\"MSG\": \"text\"}} for NEEDS TEXT orders (plain text, no {} and no [k=v];"
                    + " optional \"args\": [\"var\"] adds variables to the suffix), then: logast apply <root> --auto --fills fills.json\n");
        } else if (auto > 0) {
            sb.append("\nNext: logast apply <root> --auto\n");
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

    public static String summary(List<WorkOrder> orders) {
        StringBuilder sb = new StringBuilder();
        Map<String, Long> byRule = orders.stream().flatMap(o -> o.fixes().stream())
                .collect(Collectors.groupingBy(r -> r, TreeMap::new, Collectors.counting()));
        byRule.forEach((k, v) -> sb.append(String.format("%-26s %4d%n", k, v)));
        Map<String, Long> bySev = orders.stream().collect(Collectors.groupingBy(WorkOrder::severity, Collectors.counting()));
        sb.append("high=").append(bySev.getOrDefault("high", 0L)).append(" medium=").append(bySev.getOrDefault("medium", 0L))
                .append(" low=").append(bySev.getOrDefault("low", 0L)).append('\n');
        return sb.toString();
    }

    public static boolean atLeast(WorkOrder o, String severity) {
        return Rules.rank(o.severity()) >= Rules.rank(severity);
    }
}
