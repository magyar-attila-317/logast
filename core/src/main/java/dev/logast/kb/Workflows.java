package dev.logast.kb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** #167: per reusable workflow, the refs its callers use: who is still on an old one. */
public final class Workflows {
    private Workflows() {
    }

    public static String refs(KnowledgeBase kb, String text) {
        Map<String, Map<String, List<String>>> byWorkflow = new TreeMap<>();
        for (var e : kb.allEdges()) {
            if (!e.fact().kind().equals("calls") || !e.fact().to().startsWith("workflow:") || text != null && !text.isBlank()
                    && !e.fact().to().contains(text)) {
                continue;
            }
            String ref = String.valueOf(e.fact().attrs().getOrDefault("ref", "(same repo)"));
            byWorkflow.computeIfAbsent(e.fact().to(), k -> new TreeMap<>()).computeIfAbsent(ref, k -> new ArrayList<>())
                    .add(e.fact().from() + "  (" + KnowledgeBase.cite(e.fact().evidence(), e.header()) + ")");
        }
        if (byWorkflow.isEmpty()) {
            return "No workflow calls a reusable workflow" + (text == null || text.isBlank() ? "" : " matching '" + text + "'") + " in the facts.";
        }
        StringBuilder sb = new StringBuilder();
        byWorkflow.forEach((wf, refs) -> {
            sb.append(wf).append(refs.size() > 1 ? "  (" + refs.size() + " refs in use)" : "").append('\n');
            refs.forEach((ref, callers) -> {
                sb.append("  @").append(ref).append(" (").append(callers.size()).append("):\n");
                callers.forEach(c -> sb.append("  - ").append(c).append('\n'));
            });
        });
        return sb.toString();
    }
}
