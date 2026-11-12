package dev.logast.graph;

import dev.logast.model.CallSite;
import dev.logast.model.MethodInfo;

import java.util.*;

public final class CallGraph {
    public enum Async { NONE, MANAGED, RAW }

    /** @param ambiguous the call site dispatches to more than one source implementation */
    public record Edge(MethodInfo from, MethodInfo to, CallSite site, boolean ambiguous, Async async) {
    }

    private final Map<String, List<Edge>> out = new HashMap<>();
    private final Map<String, List<Edge>> in = new HashMap<>();
    private int ambiguousSites;
    private int edgeCount;

    public static CallGraph build(CodeIndex index) {
        CallGraph g = new CallGraph();
        for (MethodInfo m : index.methods()) {
            for (CallSite site : m.facts().calls) {
                List<MethodInfo> targets = index.resolve(site.type());
                if (targets.size() > 1) {
                    g.ambiguousSites++;
                }
                for (MethodInfo t : targets) {
                    Async async = site.async() ? (site.asyncManaged() ? Async.MANAGED : Async.RAW)
                            : t.hasAnnotation("org.springframework.scheduling.annotation.Async") ? Async.MANAGED : Async.NONE;
                    Edge e = new Edge(m, t, site, targets.size() > 1, async);
                    g.out.computeIfAbsent(m.key(), k -> new ArrayList<>()).add(e);
                    g.in.computeIfAbsent(t.key(), k -> new ArrayList<>()).add(e);
                    g.edgeCount++;
                }
            }
        }
        return g;
    }

    public List<Edge> out(MethodInfo m) {
        return out.getOrDefault(m.key(), List.of());
    }

    public List<Edge> in(MethodInfo m) {
        return in.getOrDefault(m.key(), List.of());
    }

    public int edgeCount() {
        return edgeCount;
    }

    public int ambiguousSites() {
        return ambiguousSites;
    }
}
