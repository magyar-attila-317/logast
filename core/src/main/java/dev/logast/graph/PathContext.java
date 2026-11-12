package dev.logast.graph;

import dev.logast.model.Entrypoint;
import dev.logast.model.MdcOp;
import dev.logast.model.MethodInfo;

import java.util.*;

/**
 * What is known along call paths: which entrypoints reach a method (and how), and which MDC keys are
 * guaranteed to be set when the method runs (intersection over all incoming paths).
 */
public final class PathContext {
    private final CallGraph graph;
    private final List<Entrypoint> entrypoints;
    /** entrypoint method key -> (reached method key -> edge it was first reached through; null for the root) */
    private final Map<String, Map<String, CallGraph.Edge>> parents = new LinkedHashMap<>();
    private final Map<String, Set<String>> mdcIn = new HashMap<>();

    private final Map<String, Set<String>> extraEntryKeys;
    private final Map<String, Set<String>> extraOwnKeys;

    public PathContext(CallGraph graph, List<Entrypoint> entrypoints, Set<String> ambient,
                       Set<String> httpFilterKeys, boolean decoratorPresent) {
        this(graph, entrypoints, ambient, httpFilterKeys, decoratorPresent, Map.of());
    }

    /** @param extraEntryKeys MDC keys that planned fixes will set at an entrypoint (method key -> keys) */
    public PathContext(CallGraph graph, List<Entrypoint> entrypoints, Set<String> ambient,
                       Set<String> httpFilterKeys, boolean decoratorPresent, Map<String, Set<String>> extraEntryKeys) {
        this(graph, entrypoints, ambient, httpFilterKeys, decoratorPresent, extraEntryKeys, Map.of());
    }

    /** @param extraOwnKeys MDC keys planned fixes will set for a whole method body (method key -> keys) */
    public PathContext(CallGraph graph, List<Entrypoint> entrypoints, Set<String> ambient, Set<String> httpFilterKeys,
                       boolean decoratorPresent, Map<String, Set<String>> extraEntryKeys, Map<String, Set<String>> extraOwnKeys) {
        this.graph = graph;
        this.entrypoints = entrypoints;
        this.extraEntryKeys = extraEntryKeys;
        this.extraOwnKeys = extraOwnKeys;
        for (Entrypoint ep : entrypoints) {
            parents.put(ep.method().key(), bfs(ep.method()));
        }
        computeMdc(ambient, httpFilterKeys, decoratorPresent);
    }

    private Map<String, CallGraph.Edge> bfs(MethodInfo root) {
        Map<String, CallGraph.Edge> parent = new LinkedHashMap<>();
        parent.put(root.key(), null);
        Deque<MethodInfo> queue = new ArrayDeque<>(List.of(root));
        while (!queue.isEmpty()) {
            MethodInfo m = queue.poll();
            for (CallGraph.Edge e : graph.out(m)) {
                if (!parent.containsKey(e.to().key())) {
                    parent.put(e.to().key(), e);
                    queue.add(e.to());
                }
            }
        }
        return parent;
    }

    private void computeMdc(Set<String> ambient, Set<String> filterKeys, boolean decoratorPresent) {
        Deque<MethodInfo> work = new ArrayDeque<>();
        for (Entrypoint ep : entrypoints) {
            Set<String> base = new TreeSet<>(ambient);
            if (ep.kind() == Entrypoint.Kind.HTTP) {
                base.addAll(filterKeys);
            }
            base.addAll(extraEntryKeys.getOrDefault(ep.method().key(), Set.of()));
            if (meet(ep.method(), base)) {
                work.add(ep.method());
            }
        }
        while (!work.isEmpty()) {
            MethodInfo m = work.poll();
            Set<String> out = new TreeSet<>(mdcIn.get(m.key()));
            out.addAll(ownKeys(m));
            out.addAll(extraOwnKeys.getOrDefault(m.key(), Set.of()));
            for (CallGraph.Edge e : graph.out(m)) {
                Set<String> passed = switch (e.async()) {
                    case NONE -> out;
                    case MANAGED -> decoratorPresent ? out : Set.of();
                    case RAW -> Set.of();
                };
                if (meet(e.to(), passed)) {
                    work.add(e.to());
                }
            }
        }
    }

    private boolean meet(MethodInfo m, Set<String> incoming) {
        Set<String> current = mdcIn.get(m.key());
        if (current == null) {
            mdcIn.put(m.key(), new TreeSet<>(incoming));
            return true;
        }
        return current.retainAll(incoming);
    }

    /** Literal MDC keys this method sets itself. */
    public static Set<String> ownKeys(MethodInfo m) {
        Set<String> keys = new TreeSet<>();
        for (MdcOp op : m.facts().mdc) {
            if ((op.kind() == MdcOp.Kind.PUT || op.kind() == MdcOp.Kind.PUT_CLOSEABLE) && op.key() != null) {
                keys.add(op.key());
            }
        }
        return keys;
    }

    /** MDC keys guaranteed on entry to {@code m}; empty when unreachable. */
    public Set<String> mdcAtEntry(MethodInfo m) {
        return mdcIn.getOrDefault(m.key(), Set.of());
    }

    /** MDC keys available inside {@code m}: entry keys plus the ones it sets itself. */
    public Set<String> mdcInside(MethodInfo m) {
        Set<String> s = new TreeSet<>(mdcAtEntry(m));
        s.addAll(ownKeys(m));
        s.addAll(extraOwnKeys.getOrDefault(m.key(), Set.of()));
        return s;
    }

    public boolean reachable(MethodInfo m) {
        return parents.values().stream().anyMatch(p -> p.containsKey(m.key()));
    }

    public List<Entrypoint> entrypointsReaching(MethodInfo m) {
        return entrypoints.stream().filter(ep -> parents.get(ep.method().key()).containsKey(m.key())).toList();
    }

    /** Up to {@code max} shortest paths, rendered like {@code POST /orders → OrderController.create → X.y}. */
    public List<String> paths(MethodInfo m, int max) {
        List<List<String>> all = new ArrayList<>();
        for (Entrypoint ep : entrypoints) {
            Map<String, CallGraph.Edge> parent = parents.get(ep.method().key());
            if (!parent.containsKey(m.key())) {
                continue;
            }
            LinkedList<String> path = new LinkedList<>();
            String cur = m.key();
            CallGraph.Edge e;
            while ((e = parent.get(cur)) != null) {
                path.addFirst(e.to().label() + (e.async() != CallGraph.Async.NONE ? " (async)" : ""));
                cur = e.from().key();
            }
            path.addFirst(ep.method().label());
            path.addFirst(ep.label());
            all.add(path);
        }
        all.sort(Comparator.comparingInt(List::size));
        return all.stream().limit(max).map(p -> String.join(" → ", dedupe(p))).toList();
    }

    private static List<String> dedupe(List<String> p) {
        List<String> out = new ArrayList<>();
        for (String s : p) {
            if (out.isEmpty() || !out.getLast().equals(s)) {
                out.add(s);
            }
        }
        return out;
    }

    public List<Entrypoint> entrypoints() {
        return entrypoints;
    }
}
