package dev.logast;

import dev.logast.graph.CallGraph;
import dev.logast.model.Entrypoint;
import dev.logast.model.LogCall;
import dev.logast.model.MethodInfo;
import dev.logast.graph.PathContext;

import java.util.*;
import java.util.stream.Collectors;

/** Renders the call tree below an entrypoint with logging / MDC / async markers. */
public final class Explainer {
    private final Analysis analysis;
    private final Map<String, List<String>> ordersByMethod;

    public Explainer(Analysis analysis, Map<String, List<String>> ordersByMethod) {
        this.analysis = analysis;
        this.ordersByMethod = ordersByMethod;
    }

    public String listEntrypoints() {
        StringBuilder sb = new StringBuilder();
        for (Entrypoint ep : analysis.entrypoints) {
            Double rate = analysis.traffic.perDay(ep.label());
            sb.append(String.format("%-48s %-34s %-13s %s%n", ep.label(), ep.method().label(),
                    ep.observedByFramework() ? "observed" : "NOT observed", rate == null ? "" : dev.logast.rules.Text.oneLine(String.format("%,.0f/day", rate), 30)));
        }
        return sb.toString();
    }

    public String explain(String filter, int maxDepth) {
        StringBuilder sb = new StringBuilder();
        for (Entrypoint ep : analysis.entrypoints) {
            if (filter != null && !ep.label().contains(filter) && !ep.method().label().contains(filter)) {
                continue;
            }
            sb.append(ep.label()).append("  [").append(ep.kind().name().toLowerCase(Locale.ROOT))
                    .append(ep.observedByFramework() ? ", observed" : ", NOT observed")
                    .append(analysis.traffic.perDay(ep.label()) == null ? "" : String.format(", %,.0f calls/day", analysis.traffic.perDay(ep.label())))
                    .append("]  mdc on entry: ")
                    .append(String.join(", ", analysis.paths.mdcAtEntry(ep.method()))).append('\n');
            node(sb, ep.method(), "", true, 0, maxDepth, new HashSet<>(), new HashSet<>(), null);
            sb.append('\n');
        }
        return sb.toString();
    }

    private void node(StringBuilder sb, MethodInfo m, String indent, boolean last, int depth, int maxDepth,
                      Set<String> onPath, Set<String> printed, CallGraph.Edge via) {
        sb.append(indent).append(last ? "└─ " : "├─ ").append(m.label());
        if (via != null && via.ambiguous()) {
            sb.append(" (1 of ").append(analysis.index.resolve(via.site().type()).size()).append(" impls)");
        }
        if (via != null && via.async() != CallGraph.Async.NONE) {
            sb.append(" ⇢async/").append(via.async().name().toLowerCase(Locale.ROOT));
        }
        sb.append(markers(m));
        if (onPath.contains(m.key())) {
            sb.append("  ↻ recursive\n");
            return;
        }
        if (!printed.add(m.key()) && !analysis.graph.out(m).isEmpty()) {
            sb.append("  (expanded above)\n");
            return;
        }
        sb.append('\n');
        if (depth >= maxDepth) {
            return;
        }
        onPath.add(m.key());
        List<CallGraph.Edge> edges = new ArrayList<>(analysis.graph.out(m));
        Set<String> seen = new HashSet<>();
        edges.removeIf(e -> !seen.add(e.to().key()));
        for (int i = 0; i < edges.size(); i++) {
            node(sb, edges.get(i).to(), indent + (last ? "   " : "│  "), i == edges.size() - 1, depth + 1, maxDepth,
                    onPath, printed, edges.get(i));
        }
        onPath.remove(m.key());
    }

    private String markers(MethodInfo m) {
        StringBuilder sb = new StringBuilder();
        Map<String, Long> levels = m.facts().logs.stream()
                .collect(Collectors.groupingBy(l -> l.level().name(), TreeMap::new, Collectors.counting()));
        if (!levels.isEmpty()) {
            sb.append("  log[").append(levels.entrySet().stream()
                    .map(e -> e.getValue() > 1 ? e.getKey() + "×" + e.getValue() : e.getKey())
                    .collect(Collectors.joining(","))).append(']');
        }
        Set<String> own = PathContext.ownKeys(m);
        if (!own.isEmpty()) {
            sb.append("  mdc+[").append(String.join(",", own)).append(']');
        }
        if (!m.facts().asyncHops.isEmpty()) {
            sb.append("  hands-off[").append(m.facts().asyncHops.stream().map(h -> h.api()).distinct()
                    .collect(Collectors.joining(","))).append(']');
        }
        long swallowed = m.facts().catches.stream().filter(c -> c.swallows()).count();
        if (swallowed > 0) {
            sb.append("  swallows×").append(swallowed);
        }
        List<String> orders = ordersByMethod.getOrDefault(m.key(), List.of());
        if (!orders.isEmpty()) {
            sb.append("  ⚑ ").append(String.join(" ", orders));
        }
        return sb.toString();
    }

    static boolean logsAtLeast(List<LogCall> logs, dev.logast.model.Level min) {
        return logs.stream().anyMatch(l -> l.level().compareTo(min) >= 0);
    }
}
