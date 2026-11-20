package dev.logast.kb;

import dev.logast.facts.Edge;
import dev.logast.facts.Node;
import dev.logast.facts.Provenance;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * #164, #165: links across services and what follows from them. Derived when facts load: an HTTP call
 * {@code reaches} the endpoints with its method and path (its host decides between services), and a table named
 * without a schema is the {@code same-as} the one schema that has it. Then lineage (downstream and upstream) and the
 * risks lineage shows: database handoffs that rely on timing, tables several services write, cross-schema reads.
 */
public final class Lineage {
    private Lineage() {
    }

    /** One derived edge and the fact it rests on (for its header and file). */
    record Derived(Edge edge, KnowledgeBase.Fact<?> basis) {
    }

    // ---- derived links ------------------------------------------------------------------------------------------------

    static List<Derived> derive(KnowledgeBase kb) {
        List<Derived> out = new ArrayList<>();
        http(kb, out);
        sameTable(kb, out);
        runners(kb, out);
        return out;
    }

    private static void http(KnowledgeBase kb, List<Derived> out) {
        List<KnowledgeBase.Fact<Node>> endpoints = kb.nodes().stream().filter(n -> n.fact().kind().equals("endpoint")).toList();
        Set<String> seen = new LinkedHashSet<>();
        for (KnowledgeBase.Fact<Node> c : kb.nodes()) {
            Node call = c.fact();
            if (!call.kind().equals("call") || !"http".equals(call.attrs().get("transport")) || !seen.add(call.id())) {
                continue;
            }
            String method = str(call.attrs().get("method"));
            String target = str(call.attrs().get("target"));
            if (target == null) {
                continue;
            }
            Map<String, List<Node>> byService = new LinkedHashMap<>();
            for (KnowledgeBase.Fact<Node> e : endpoints) {
                Node ep = e.fact();
                String m = str(ep.attrs().get("method"));
                if ((method == null || m == null || method.equalsIgnoreCase(m)) && pathMatches(target, str(ep.attrs().get("path")))) {
                    byService.computeIfAbsent(serviceOf(ep.id()), k -> new ArrayList<>()).add(ep);
                }
            }
            if (byService.isEmpty()) {
                continue;
            }
            String host = host(str(call.attrs().get("base")));
            if (host != null) {
                Set<String> named = servicesNamed(kb, host);
                for (var e : byService.entrySet()) {
                    if (named.contains(e.getKey())) {
                        e.getValue().forEach(ep -> out.add(new Derived(edge(call, ep, 0.9, "http:host"), c)));
                    }
                }
                // a host that names none of them: another system, or a stale URL — no link
            } else {
                double confidence = byService.size() == 1 ? 0.6 : 0.3;
                byService.values().forEach(eps -> eps.forEach(ep -> out.add(new Derived(edge(call, ep, confidence,
                        byService.size() == 1 ? "http:path" : "http:path-ambiguous"), c))));
            }
        }
    }

    private static Edge edge(Node call, Node endpoint, double confidence, String via) {
        return new Edge(call.id(), "reaches", endpoint.id(), endpoint.env(), Map.of(), Provenance.INFERRED, confidence, via, null, call.evidence());
    }

    /** The services a host names: by name or application name, a Kubernetes Service that routes their workload, knowledge.yml. */
    private static Set<String> servicesNamed(KnowledgeBase kb, String host) {
        Set<String> out = new LinkedHashSet<>();
        for (KnowledgeBase.Fact<Node> n : kb.nodes()) {
            if (n.fact().kind().equals("service")) {
                String name = n.fact().id().substring("service:".length());
                if (name.equals(host) || host.equals(n.fact().attrs().get("application"))) {
                    out.add(name);
                }
            }
        }
        for (var r : kb.outgoing("k8s-service:" + host)) {
            if (r.fact().kind().equals("routes")) {
                for (var d : kb.incoming(r.fact().to())) {
                    if (d.fact().kind().equals("deployed-as")) {
                        out.add(d.fact().from().substring("service:".length()));
                    }
                }
            }
        }
        String alias = kb.knowledge().serviceFor(host);
        if (alias != null) {
            out.add(alias);
        }
        return out;
    }

    static String host(String base) {
        if (base == null || base.isBlank()) {
            return null;
        }
        String h = base.replaceFirst("^[a-z][a-z0-9+.-]*://", "");
        h = h.replaceFirst("[/:].*$", "");
        h = h.replaceFirst("\\..*$", "");   // shipping.finance.svc.cluster.local → shipping
        return h.isBlank() ? null : h.toLowerCase(Locale.ROOT);
    }

    /** Segment by segment; a path variable ({@code {id}}, {@code {?}}) on either side matches any segment. */
    static boolean pathMatches(String a, String b) {
        if (b == null) {
            return false;
        }
        String[] x = a.replaceAll("/+$", "").split("/");
        String[] y = b.replaceAll("/+$", "").split("/");
        if (x.length != y.length) {
            return false;
        }
        for (int i = 0; i < x.length; i++) {
            boolean var = x[i].startsWith("{") || y[i].startsWith("{");
            if (!var && !x[i].equals(y[i])) {
                return false;
            }
        }
        return true;
    }

    /** {@code table:audit_event} is {@code table:public.audit_event} when exactly one schema has that table. */
    private static void sameTable(KnowledgeBase kb, List<Derived> out) {
        Set<String> tables = new TreeSet<>();
        kb.nodes().forEach(n -> {
            if (n.fact().id().startsWith("table:")) {
                tables.add(n.fact().id());
            }
        });
        kb.allEdges().forEach(e -> {
            if (e.fact().to().startsWith("table:")) {
                tables.add(e.fact().to());
            }
        });
        for (String t : tables) {
            String name = t.substring("table:".length());
            if (name.contains(".")) {
                continue;
            }
            List<String> qualified = tables.stream().filter(q -> q.endsWith("." + name) && q.indexOf('.') == q.lastIndexOf('.')).toList();
            if (qualified.size() == 1) {
                KnowledgeBase.Fact<Edge> basis = kb.incoming(t).isEmpty() ? null : kb.incoming(t).getFirst();
                if (basis != null) {
                    out.add(new Derived(new Edge(t, "same-as", qualified.getFirst(), null, Map.of(), Provenance.INFERRED, 0.8,
                            "table:schema", null, basis.fact().evidence()), basis));
                }
            }
        }
    }

    /** GitHub's default labels of a self-hosted runner: present on every one, so they need no match (#173). */
    static final java.util.Set<String> DEFAULT_LABELS = java.util.Set.of("self-hosted", "linux", "windows", "macos", "x64", "arm64", "arm");

    /** #173: a workflow on self-hosted labels runs on the runner hosts whose labels cover them. */
    private static void runners(KnowledgeBase kb, List<Derived> out) {
        List<KnowledgeBase.Fact<Node>> runners = kb.nodes().stream().filter(n -> n.fact().kind().equals("runner-host")).toList();
        for (KnowledgeBase.Fact<Node> w : kb.nodes()) {
            if (!w.fact().kind().equals("workflow") || !(w.fact().attrs().get("runsOn") instanceof List<?> on)
                    || !on.contains("self-hosted")) {
                continue;
            }
            Set<String> needed = new TreeSet<>();
            on.forEach(l -> needed.add(String.valueOf(l).toLowerCase(Locale.ROOT)));
            needed.removeAll(DEFAULT_LABELS);
            for (KnowledgeBase.Fact<Node> r : runners) {
                Set<String> has = new TreeSet<>();
                if (r.fact().attrs().get("labels") instanceof List<?> ls) {
                    ls.forEach(l -> has.add(String.valueOf(l).toLowerCase(Locale.ROOT)));
                }
                if (has.containsAll(needed)) {
                    out.add(new Derived(new Edge(w.fact().id(), "runs-on", r.fact().id(), null, Map.of(), Provenance.INFERRED, 0.8,
                            "runner-labels", null, w.fact().evidence()), w));
                }
            }
        }
    }

    public static String runners(KnowledgeBase kb) {
        StringBuilder sb = new StringBuilder();
        List<KnowledgeBase.Fact<Node>> runners = kb.nodes().stream().filter(n -> n.fact().kind().equals("runner-host")).toList();
        for (KnowledgeBase.Fact<Node> r : runners) {
            Map<String, Object> a = r.fact().attrs();
            sb.append("- ").append(r.fact().id().substring("runner-host:".length())).append("  groups ").append(a.get("groups"))
                    .append(", labels ").append(a.get("labels")).append(a.get("count") == null ? "" : ", " + a.get("count") + " runner(s)")
                    .append("  (").append(KnowledgeBase.cite(r.fact().evidence(), r.header())).append(")\n");
            kb.incoming(r.fact().id()).stream().filter(e -> e.fact().kind().equals("runs-on"))
                    .forEach(e -> sb.append("    runs ").append(e.fact().from()).append('\n'));
        }
        List<String> unplaced = new ArrayList<>();
        for (KnowledgeBase.Fact<Node> w : kb.nodes()) {
            if (w.fact().kind().equals("workflow") && w.fact().attrs().get("runsOn") instanceof List<?> on && on.contains("self-hosted")
                    && kb.outgoing(w.fact().id()).stream().noneMatch(e -> e.fact().kind().equals("runs-on"))) {
                unplaced.add("- " + w.fact().id() + " needs " + on);
            }
        }
        if (runners.isEmpty() && unplaced.isEmpty()) {
            return "No self-hosted runner in the facts (an Ansible inventory with runner labels) and no workflow asks for one.";
        }
        StringBuilder out = new StringBuilder(runners.size() + " runner host(s):\n").append(sb);
        if (!unplaced.isEmpty()) {
            out.append("self-hosted workflows no runner's labels fit:\n");
            unplaced.forEach(l -> out.append(l).append('\n'));
        }
        return out.toString();
    }

    // ---- lineage ------------------------------------------------------------------------------------------------------

    private static final Set<String> DOWN_OUT = Set.of("calls", "publishes", "writes", "reaches", "after", "has", "runs", "same-as");
    private static final Set<String> DOWN_IN = Set.of("consumes", "reads", "active-with", "runs", "same-as");

    static String lineage(KnowledgeBase kb, String start, boolean downstream, int depth, int limit) {
        StringBuilder sb = new StringBuilder(start + (downstream ? " — downstream" : " — upstream") + ":\n");
        Set<String> seen = new LinkedHashSet<>(List.of(start));
        List<String> lines = new ArrayList<>();   // depth-first, so the tree reads top to bottom
        dfs(kb, start, downstream, 0, depth, seen, lines, limit);
        if (lines.isEmpty()) {
            return sb.append("nothing ").append(downstream ? "follows from it" : "leads to it").append(" in the facts\n").toString();
        }
        lines.forEach(l -> sb.append(l).append('\n'));
        if (lines.size() >= limit) {
            sb.append("… more (raise limit)\n");
        }
        return sb.toString();
    }

    private static void dfs(KnowledgeBase kb, String node, boolean down, int level, int maxDepth, Set<String> seen, List<String> lines, int limit) {
        if (level >= maxDepth || lines.size() >= limit) {
            return;
        }
        List<KnowledgeBase.Fact<Edge>> next = new ArrayList<>();
        for (var e : kb.outgoing(node)) {
            if ((down ? DOWN_OUT : DOWN_IN).contains(e.fact().kind())) {
                next.add(e);
            }
        }
        for (var e : kb.incoming(node)) {
            if ((down ? DOWN_IN : DOWN_OUT).contains(e.fact().kind())) {
                next.add(e);
            }
        }
        for (var e : next) {
            boolean forward = e.fact().from().equals(node);
            String other = forward ? e.fact().to() : e.fact().from();
            if (!seen.add(other) || lines.size() >= limit) {
                continue;
            }
            String rel = forward ? e.fact().kind() + " →" : "← " + e.fact().kind() + " by";
            lines.add("  ".repeat(level + 1) + "- " + rel + " " + other + (e.fact().env() == null ? "" : " [" + e.fact().env() + "]")
                    + (e.fact().provenance() == Provenance.INFERRED ? " [inferred " + e.fact().via() + "]" : "")
                    + "  (" + KnowledgeBase.cite(e.fact().evidence(), e.header()) + ")");
            dfs(kb, other, down, level + 1, maxDepth, seen, lines, limit);
        }
    }

    // ---- risks --------------------------------------------------------------------------------------------------------

    /** The service a node belongs to: {@code <kind>:<service>:…}, or the service that deploys a CronJob. */
    static String serviceOf(String id) {
        String[] p = id.split(":", 3);
        return p.length >= 2 ? p[1] : id;
    }

    private static String serviceOf(KnowledgeBase kb, String id) {
        if (id.startsWith("service:")) {
            return id.substring("service:".length());
        }
        for (var d : kb.incoming(id)) {
            if (d.fact().kind().equals("deployed-as")) {
                return d.fact().from().substring("service:".length());
            }
        }
        return serviceOf(id);
    }

    static String risks(KnowledgeBase kb, String env) {
        Map<String, String> canonical = new LinkedHashMap<>();
        kb.allEdges().forEach(e -> {
            if (e.fact().kind().equals("same-as")) {
                canonical.put(e.fact().from(), e.fact().to());
            }
        });
        Map<String, List<KnowledgeBase.Fact<Edge>>> writers = new TreeMap<>();
        Map<String, List<KnowledgeBase.Fact<Edge>>> readers = new TreeMap<>();
        for (var e : kb.allEdges()) {
            if (!(e.fact().to().startsWith("table:") || e.fact().to().startsWith("file-location:"))   // #172: files hand over too
                    || env != null && e.fact().env() != null && !e.fact().env().equals(env)) {
                continue;
            }
            String t = canonical.getOrDefault(e.fact().to(), e.fact().to());
            if (e.fact().kind().equals("writes")) {
                writers.computeIfAbsent(t, k -> new ArrayList<>()).add(e);
            } else if (e.fact().kind().equals("reads")) {
                readers.computeIfAbsent(t, k -> new ArrayList<>()).add(e);
            }
        }
        List<String> handoffs = new ArrayList<>();
        List<String> shared = new ArrayList<>();
        Map<String, Set<String>> writtenSchemas = new TreeMap<>();
        writers.forEach((t, ws) -> {   // a service's own schemas: where it writes tables no other service writes
            if (!t.startsWith("table:")) {
                return;
            }
            String name = t.substring("table:".length());
            Set<String> services = new TreeSet<>();
            ws.forEach(w -> services.add(serviceOf(kb, w.fact().from())));
            if (name.contains(".") && services.size() == 1) {
                writtenSchemas.computeIfAbsent(services.iterator().next(), k -> new TreeSet<>()).add(name.substring(0, name.indexOf('.')));
            }
        });
        for (var t : writers.entrySet()) {
            Set<String> writerServices = new TreeSet<>();
            t.getValue().forEach(w -> writerServices.add(serviceOf(kb, w.fact().from())));
            if (writerServices.size() > 1) {
                shared.add("- " + t.getKey() + ": written by " + String.join(", ", writerServices));
            }
            for (var w : t.getValue()) {
                List<String> wJobs = schedules(kb, w.fact().from(), env);
                if (wJobs.isEmpty()) {
                    continue;
                }
                for (var r : readers.getOrDefault(t.getKey(), List.of())) {
                    String ws = serviceOf(kb, w.fact().from());
                    String rs = serviceOf(kb, r.fact().from());
                    if (ws.equals(rs)) {
                        continue;
                    }
                    List<String> rJobs = schedules(kb, r.fact().from(), env);
                    if (rJobs.isEmpty()) {
                        continue;
                    }
                    boolean guarded = pipeline(kb, w.fact().from(), r.fact().from(), env);
                    handoffs.add("- " + t.getKey() + ": " + w.fact().from() + " writes (" + String.join("; ", wJobs) + "), "
                            + r.fact().from() + " reads (" + String.join("; ", rJobs) + ")"
                            + (guarded ? " — a pipeline step runs the reader after the writer" : " — NO pipeline step between them: the reader relies on timing")
                            + "  (" + KnowledgeBase.cite(w.fact().evidence(), w.header()) + ", " + KnowledgeBase.cite(r.fact().evidence(), r.header()) + ")");
                }
            }
        }
        List<String> crossSchema = new ArrayList<>();
        for (var t : readers.entrySet()) {
            if (!t.getKey().startsWith("table:")) {
                continue;
            }
            String name = t.getKey().substring("table:".length());
            if (!name.contains(".")) {
                continue;
            }
            String schema = name.substring(0, name.indexOf('.'));
            for (var r : t.getValue()) {
                String rs = serviceOf(kb, r.fact().from());
                Set<String> own = writtenSchemas.getOrDefault(rs, Set.of());
                if (!own.isEmpty() && !own.contains(schema)) {
                    crossSchema.add("- " + r.fact().from() + " reads " + t.getKey() + " (" + rs + " writes " + String.join(", ", own) + ")  ("
                            + KnowledgeBase.cite(r.fact().evidence(), r.header()) + ")");
                }
            }
        }
        StringBuilder sb = new StringBuilder("Risks" + (env == null ? "" : " in " + env) + ":\n");
        sb.append("handoffs through a table or a file between scheduled jobs of different services (").append(handoffs.size()).append("):\n");
        handoffs.forEach(l -> sb.append(l).append('\n'));
        sb.append("tables written by more than one service (").append(shared.size()).append("):\n");
        shared.forEach(l -> sb.append(l).append('\n'));
        sb.append("reads of another service's schema (").append(crossSchema.size()).append("):\n");
        crossSchema.forEach(l -> sb.append(l).append('\n'));
        return sb.toString();
    }

    /**
     * When an entrypoint runs: its own schedule ({@code @Scheduled}, a CronJob), or the schedules of the jobs that start
     * it through its profile (job → runs → profile ← active-with ← bean ← runs ← entrypoint).
     */
    static List<String> schedules(KnowledgeBase kb, String entrypoint, String env) {
        List<String> out = new ArrayList<>();
        for (String job : jobsStarting(kb, entrypoint, env)) {
            for (var n : kb.nodes()) {
                if (n.fact().id().equals(job) && (env == null || n.fact().env() == null || env.equals(n.fact().env()))) {
                    Object s = n.fact().attrs().get("schedule") != null ? n.fact().attrs().get("schedule")
                            : n.fact().attrs().get("cronResolved") != null ? n.fact().attrs().get("cronResolved") : n.fact().attrs().get("cron");
                    if (s != null) {
                        out.add((job.equals(entrypoint) ? "" : job + " ") + s + (n.fact().env() == null ? "" : " [" + n.fact().env() + "]"));
                    }
                }
            }
        }
        return out;
    }

    private static Set<String> jobsStarting(KnowledgeBase kb, String entrypoint, String env) {
        Set<String> jobs = new LinkedHashSet<>(List.of(entrypoint));
        for (var r : kb.outgoing(entrypoint)) {
            if (!r.fact().kind().equals("runs")) {
                continue;
            }
            for (var a : kb.outgoing(r.fact().to())) {
                if (!a.fact().kind().equals("active-with")) {
                    continue;
                }
                for (var j : kb.incoming(a.fact().to())) {
                    if (j.fact().kind().equals("runs") && (env == null || j.fact().env() == null || env.equals(j.fact().env()))) {
                        jobs.add(j.fact().from());
                    }
                }
            }
        }
        return jobs;
    }

    /** Whether a chain of pipeline steps ({@code after}) leads from a job that starts the writer to one that starts the reader. */
    private static boolean pipeline(KnowledgeBase kb, String writer, String reader, String env) {
        Set<String> targets = jobsStarting(kb, reader, env);
        Deque<String> queue = new ArrayDeque<>(jobsStarting(kb, writer, env));
        Set<String> seen = new LinkedHashSet<>(queue);
        while (!queue.isEmpty()) {
            String j = queue.poll();
            for (var e : kb.outgoing(j)) {
                if (e.fact().kind().equals("after") && (env == null || e.fact().env() == null || env.equals(e.fact().env()))) {
                    if (targets.contains(e.fact().to())) {
                        return true;
                    }
                    if (seen.add(e.fact().to())) {
                        queue.add(e.fact().to());
                    }
                }
            }
        }
        return false;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
