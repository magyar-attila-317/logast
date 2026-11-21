package dev.logast.kb;

import dev.logast.facts.Edge;
import dev.logast.facts.Provenance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * #214: what a workflow's script does with variables ({@code yq -i '.services.$1.image.tag = …' $VALUES}), resolved
 * through what reaches it: the step's args ({@code $1}) and env ({@code $VALUES}), then GitHub's expressions
 * ({@code ${{ inputs.service }}}, {@code ${{ env.X }}}, {@code ${{ github.event.client_payload.x }}}) through each caller
 * of the workflow (a reusable call's {@code with:}, a dispatch's inputs or payload). Each caller gets the effect, inferred:
 * {@code <caller> -writes-> file:org/k8s/charts/values-prod.yaml {key: .services.orders.image.tag}}.
 */
final class WorkflowChains {
    private static final Set<String> EFFECTS = Set.of("writes", "clones", "pushes", "installs", "applies", "dispatches");
    private static final Pattern SHELL_VAR = Pattern.compile("\\$(?:\\{([A-Za-z_][A-Za-z0-9_]*|[0-9])}|([A-Za-z_][A-Za-z0-9_]*|[0-9]))");
    private static final Pattern GH_EXPR = Pattern.compile("\\$\\{\\{\\s*(inputs|env|github\\.event\\.client_payload|github\\.event\\.inputs)\\.([\\w-]+)\\s*}}");

    private WorkflowChains() {
    }

    static List<Lineage.Derived> derive(KnowledgeBase kb) {
        List<Lineage.Derived> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (KnowledgeBase.Fact<Edge> e : kb.allEdges()) {
            Edge eff = e.fact();
            if (!EFFECTS.contains(eff.kind()) || eff.provenance() == Provenance.INFERRED || !hasVariable(eff)) {
                continue;
            }
            // the workflows whose steps reach this effect, with what the step binds: a script's runs, or the workflow itself
            List<Map.Entry<String, Map<String, String>>> bound = new ArrayList<>();
            if (eff.from().startsWith("script:")) {
                for (KnowledgeBase.Fact<Edge> r : kb.incoming(eff.from())) {
                    if (r.fact().kind().equals("runs") && r.fact().from().startsWith("workflow:")) {
                        bound.add(Map.entry(r.fact().from(), shellBindings(r.fact().attrs())));
                    }
                }
            } else if (eff.from().startsWith("workflow:")) {
                bound.add(Map.entry(eff.from(), Map.of()));
            }
            for (var b : bound) {
                String target = shell(eff.to(), b.getValue());
                Map<String, Object> attrs = shellAttrs(eff.attrs(), b.getValue());
                for (var caller : callers(kb, b.getKey())) {
                    String to = github(target, caller.getValue());
                    Map<String, Object> a = githubAttrs(attrs, caller.getValue());
                    if (to.equals(eff.to()) && a.equals(eff.attrs()) || hasGithubInput(to)) {
                        continue;   // nothing resolved, or the caller does not pass it
                    }
                    a.put("through", caller.getKey().equals(b.getKey()) ? List.of(eff.from()) : List.of(b.getKey(), eff.from()));
                    String from = caller.getKey();
                    if (!seen.add(from + " " + eff.kind() + " " + to + " " + a)) {
                        continue;
                    }
                    out.add(new Lineage.Derived(new Edge(from, eff.kind(), to, eff.env(), a, Provenance.INFERRED, 0.8,
                            "workflow-chain", null, eff.evidence()), e));
                }
            }
        }
        return out;
    }

    private static boolean hasVariable(Edge e) {
        return e.to().contains("$") || e.attrs().values().stream().anyMatch(v -> String.valueOf(v).contains("$"));
    }

    private static boolean hasGithubInput(String s) {
        Matcher m = GH_EXPR.matcher(s);
        while (m.find()) {
            if (!m.group(1).equals("env")) {
                return true;
            }
        }
        return false;
    }

    /** What a {@code runs} edge binds: its env as written, and {@code 1..n} for its args. */
    private static Map<String, String> shellBindings(Map<String, Object> runs) {
        Map<String, String> b = new LinkedHashMap<>();
        if (runs.get("env") instanceof Map<?, ?> env) {
            env.forEach((k, v) -> b.put(String.valueOf(k), String.valueOf(v)));
        }
        if (runs.get("args") instanceof List<?> args) {
            for (int i = 0; i < args.size(); i++) {
                b.put(String.valueOf(i + 1), String.valueOf(args.get(i)));
            }
        }
        return b;
    }

    /**
     * The workflow's callers, each with what it passes as {@code inputs.x} / {@code client_payload.x}; the workflow itself
     * (binding only its env) when nothing calls it.
     */
    private static List<Map.Entry<String, Map<String, String>>> callers(KnowledgeBase kb, String workflow) {
        List<Map.Entry<String, Map<String, String>>> out = new ArrayList<>();
        for (KnowledgeBase.Fact<Edge> in : kb.incoming(workflow)) {
            Edge c = in.fact();
            if (c.kind().equals("calls") && c.attrs().get("with") instanceof Map<?, ?> with) {
                out.add(Map.entry(c.from(), prefixed("inputs.", with)));
            } else if (c.kind().equals("dispatches") && c.attrs().get("inputs") instanceof Map<?, ?> inputs) {
                Map<String, String> b = prefixed("inputs.", inputs);
                b.putAll(prefixed("github.event.inputs.", inputs));
                out.add(Map.entry(c.from(), b));
            } else if (c.kind().equals("triggers") && c.from().startsWith("event:github:")) {
                for (KnowledgeBase.Fact<Edge> d : kb.incoming(c.from())) {
                    if (d.fact().kind().equals("dispatches") && d.fact().attrs().get("payload") instanceof Map<?, ?> p) {
                        out.add(Map.entry(d.fact().from(), prefixed("github.event.client_payload.", p)));
                    }
                }
            }
        }
        if (out.isEmpty()) {
            out.add(Map.entry(workflow, Map.of()));
        }
        return out;
    }

    private static Map<String, String> prefixed(String prefix, Map<?, ?> m) {
        Map<String, String> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(prefix + k, String.valueOf(v)));
        return out;
    }

    /** Shell variables replaced by what the step binds (an env value may itself be a GitHub expression). */
    static String shell(String s, Map<String, String> b) {
        if (s.indexOf('$') < 0 || b.isEmpty()) {
            return s;
        }
        Matcher m = SHELL_VAR.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = b.get(m.group(1) != null ? m.group(1) : m.group(2));
            m.appendReplacement(sb, Matcher.quoteReplacement(v != null ? v : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** GitHub expressions replaced by what the caller passes ({@code env.x} stays: the caller's env is not the callee's). */
    static String github(String s, Map<String, String> b) {
        if (!s.contains("${{") || b.isEmpty()) {
            return s;
        }
        Matcher m = GH_EXPR.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = b.get(m.group(1) + "." + m.group(2));
            m.appendReplacement(sb, Matcher.quoteReplacement(v != null ? v : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static Map<String, Object> shellAttrs(Map<String, Object> attrs, Map<String, String> b) {
        return mapStrings(attrs, v -> shell(v, b));
    }

    private static Map<String, Object> githubAttrs(Map<String, Object> attrs, Map<String, String> b) {
        return mapStrings(attrs, v -> github(v, b));
    }

    private static Map<String, Object> mapStrings(Map<String, Object> attrs, java.util.function.UnaryOperator<String> f) {
        Map<String, Object> out = new java.util.TreeMap<>();
        attrs.forEach((k, v) -> {
            if (v instanceof String s) {
                out.put(k, f.apply(s));
            } else if (v instanceof Map<?, ?> m) {
                Map<String, Object> inner = new java.util.TreeMap<>();
                m.forEach((ik, iv) -> inner.put(String.valueOf(ik), iv instanceof String is ? f.apply(is) : iv));
                out.put(k, inner);
            } else {
                out.put(k, v);
            }
        });
        return out;
    }
}
