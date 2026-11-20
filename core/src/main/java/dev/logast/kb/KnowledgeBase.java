package dev.logast.kb;

import dev.logast.facts.Edge;
import dev.logast.facts.Evidence;
import dev.logast.facts.FactReader;
import dev.logast.facts.Header;
import dev.logast.facts.Node;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * #156: the facts of many repos in memory (docs/facts.md), and the questions {@code logast mcp} answers over them.
 * Every answer is plain text that cites where each fact comes from ({@code repo/file:line@commit}).
 */
public final class KnowledgeBase {
    /** A fact with the header of the file it came from (the default repo and commit of its evidence). */
    public record Fact<T>(T fact, Header header, Path file) {
    }

    private final Map<String, List<Fact<Node>>> nodes = new TreeMap<>();
    private final List<Fact<Edge>> edges = new ArrayList<>();
    private final Map<String, List<Fact<Edge>>> out = new HashMap<>();
    private final Map<String, List<Fact<Edge>>> in = new HashMap<>();
    private final List<String> problems = new ArrayList<>();
    private final List<Path> files = new ArrayList<>();
    private final List<String> ambiguous = new ArrayList<>();
    private final Knowledge knowledge = Knowledge.empty();
    private final List<String> decisionNotes = new ArrayList<>();
    private final Set<String> repos = new java.util.TreeSet<>();

    private KnowledgeBase() {
    }

    /** Loads every {@code *.jsonl} file under the given files and folders; broken lines are left out and listed. */
    public static KnowledgeBase load(Collection<Path> paths) {
        KnowledgeBase kb = new KnowledgeBase();
        for (Path f : factFiles(paths)) {
            if (isKnowledge(f)) {   // #163: the company's glossary, names and decisions
                kb.knowledge.load(f);
                kb.problems.addAll(kb.knowledge.problems().stream().filter(p -> !kb.problems.contains(p)).toList());
                continue;
            }
            kb.files.add(f);
            FactReader.Result r;
            try (Reader in = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                r = FactReader.read(in);
            } catch (IOException e) {
                kb.problems.add(f + ": " + e.getMessage());
                continue;
            }
            r.problems().forEach(p -> kb.problems.add(f + ":" + p.line() + ": " + p.message()));
            if (r.header() != null) {
                kb.repos.add(r.header().repo());
            }
            for (Node n : r.nodes()) {
                kb.nodes.computeIfAbsent(n.id(), k -> new ArrayList<>()).add(new Fact<>(n, r.header(), f));
            }
            for (Edge e : r.edges()) {
                Fact<Edge> fe = new Fact<>(e, r.header(), f);
                kb.edges.add(fe);
                kb.out.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(fe);
                kb.in.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(fe);
            }
        }
        kb.derive();
        return kb;
    }

    /** #161: links the facts imply but no file states: which deployments run which service. */
    private void derive() {
        var r = Identity.links(nodes(), out, knowledge);
        for (Identity.Link l : r.getKey()) {
            Edge edge = decide(Identity.edge(l), l.workloadFact().header());
            if (edge == null) {
                continue;   // rejected in knowledge.yml
            }
            Fact<Edge> fe = new Fact<>(edge, l.workloadFact().header(), l.workloadFact().file());
            edges.add(fe);
            out.computeIfAbsent(fe.fact().from(), k -> new ArrayList<>()).add(fe);
            in.computeIfAbsent(fe.fact().to(), k -> new ArrayList<>()).add(fe);
        }
        ambiguous.addAll(r.getValue());
        for (Lineage.Derived d : Lineage.derive(this)) {   // #164: HTTP calls → endpoints, schema-less table names
            Fact<Edge> fe = new Fact<>(d.edge(), d.basis().header(), d.basis().file());
            edges.add(fe);
            out.computeIfAbsent(fe.fact().from(), k -> new ArrayList<>()).add(fe);
            in.computeIfAbsent(fe.fact().to(), k -> new ArrayList<>()).add(fe);
        }
    }

    /** The edges arriving at a node. */
    public List<Fact<Edge>> incoming(String id) {
        return in.getOrDefault(id, List.of());
    }

    /** Every edge, derived ones included. */
    public List<Fact<Edge>> allEdges() {
        return List.copyOf(edges);
    }

    /**
     * An inferred edge after knowledge.yml's decisions: null when rejected; marked approved (with who) when approved on
     * the evidence it has now; marked stale when the evidence changed since, so it is decided again rather than applied.
     */
    private Edge decide(Edge e, Header h) {
        String link = e.from() + " -" + e.kind() + "-> " + e.to();
        String now = cite(e.evidence(), h);
        for (Knowledge.Decision d : knowledge.decisions()) {
            if (!d.link().equals(link) || d.env() != null && !d.env().equals(e.env())) {
                continue;
            }
            Map<String, Object> attrs = new TreeMap<>(e.attrs());
            if (d.evidence() != null && !now.startsWith(d.evidence())) {
                attrs.put("decision", "stale: " + (d.approve() ? "approved" : "rejected") + " on " + d.evidence() + ", the evidence is now " + now);
                decisionNotes.add(link + (e.env() == null ? "" : " [" + e.env() + "]") + ": decided on " + d.evidence() + ", now " + now);
            } else if (!d.approve()) {
                return null;
            } else {
                attrs.put("decision", "approved" + (d.by() == null ? "" : " by " + d.by()));
            }
            return new Edge(e.from(), e.kind(), e.to(), e.env(), attrs, e.provenance(), e.confidence(), e.via(), e.sources(), e.evidence());
        }
        return e;
    }

    /** The citation of a node now (its first fact), or of the first edge that names it; null when the facts do not have it. */
    public String evidenceOf(String id) {
        List<Fact<Node>> ns = nodes.getOrDefault(id, List.of());
        if (!ns.isEmpty()) {
            return cite(ns.getFirst().fact().evidence(), ns.getFirst().header());
        }
        List<Fact<Edge>> es = new ArrayList<>(out.getOrDefault(id, List.of()));
        es.addAll(in.getOrDefault(id, List.of()));
        return es.isEmpty() ? null : cite(es.getFirst().fact().evidence(), es.getFirst().header());
    }

    /** Whether the evidence recorded with a proposal differs from what the facts say now. */
    public boolean stale(Map<String, String> recorded) {
        return recorded.entrySet().stream().anyMatch(e -> !e.getValue().equals(evidenceOf(e.getKey())));
    }

    /** #168: the business flows: approved (knowledge.yml) and proposed (knowledge.proposed.yml), with their nodes. */
    public String flows(String text) {
        StringBuilder sb = new StringBuilder();
        for (Knowledge.Flow f : knowledge.flows().values()) {
            if (text != null && !text.isBlank() && !f.name().toLowerCase(java.util.Locale.ROOT).contains(text.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            sb.append("- ").append(f.name()).append(f.proposed() ? " (proposed by " + f.by() + ", not approved yet)" : "")
                    .append(stale(f.evidence()) ? " [STALE: the evidence of its nodes changed]" : "").append('\n');
            if (f.description() != null) {
                sb.append("  ").append(f.description()).append('\n');
            }
            f.nodes().forEach(n -> sb.append("  · ").append(n).append(evidenceOf(n) == null ? " (not in the facts)" : "").append('\n'));
            if (!f.sources().isEmpty()) {
                sb.append("  sources: ").append(String.join(", ", f.sources())).append('\n');
            }
        }
        return sb.isEmpty() ? "No business flow" + (text == null || text.isBlank() ? "" : " matching '" + text + "'")
                + ": name one with propose_flow, or in knowledge.yml's flows:." : sb.toString();
    }

    /** Workloads more than one service claims by the same rule: not linked, for a person to decide. */
    public List<String> ambiguous() {
        return List.copyOf(ambiguous);
    }

    /** The facts files the paths name: files as given, folders searched for {@code *.jsonl}, sorted. */
    public static List<Path> factFiles(Collection<Path> paths) {
        Set<Path> out = new java.util.TreeSet<>();
        for (Path p : paths) {
            if (Files.isRegularFile(p)) {
                out.add(p.toAbsolutePath().normalize());
            } else if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.walk(p)) {
                    s.filter(f -> Files.isRegularFile(f) && (f.getFileName().toString().endsWith(".jsonl") || isKnowledge(f))
                                    && !f.toString().contains("/.git/"))
                            .forEach(f -> out.add(f.toAbsolutePath().normalize()));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return List.copyOf(out);
    }

    /** Every node fact, in id order (a node in several environments or files once per fact). */
    public List<Fact<Node>> nodes() {
        return nodes.values().stream().flatMap(List::stream).toList();
    }

    /** The edges leaving a node. */
    public List<Fact<Edge>> outgoing(String id) {
        return out.getOrDefault(id, List.of());
    }

    /** The repos of the loaded files (their headers). */
    public Set<String> repos() {
        return Set.copyOf(repos);
    }

    /** {@code knowledge.yml} / {@code knowledge.yaml}: what people decided, loaded with the facts (#163). */
    static boolean isKnowledge(Path f) {
        String n = f.getFileName().toString();
        return n.equals("knowledge.yml") || n.equals("knowledge.yaml") || n.equals("knowledge.proposed.yml") || n.equals("knowledge.decisions.yml");
    }

    public Knowledge knowledge() {
        return knowledge;
    }

    public List<String> problems() {
        return List.copyOf(problems);
    }

    public List<Path> files() {
        return List.copyOf(files);
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int edgeCount() {
        return edges.size();
    }

    // ---- questions ----------------------------------------------------------------------------------------------------

    /** What is loaded: files, repos, nodes and edges by kind. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(files.size()).append(" facts files, ").append(repos.size()).append(" repos: ").append(String.join(", ", repos)).append('\n');
        Map<String, Integer> nk = new TreeMap<>();
        nodes.values().forEach(l -> nk.merge(l.getFirst().fact().kind(), 1, Integer::sum));
        Map<String, Integer> ek = new TreeMap<>();
        edges.forEach(e -> ek.merge(e.fact().kind(), 1, Integer::sum));
        sb.append("nodes: ").append(nk).append('\n').append("edges: ").append(ek).append('\n');
        if (!problems.isEmpty()) {
            sb.append(problems.size()).append(" lines left out (not valid facts), e.g. ").append(problems.getFirst()).append('\n');
        }
        long linked = edges.stream().filter(e -> e.fact().kind().equals("deployed-as")).count();
        if (linked > 0) {
            sb.append(linked).append(" deployments linked to their service's code (inferred: image, name or image name)\n");
        }
        if (!knowledge.files().isEmpty()) {
            sb.append("knowledge: ").append(knowledge.files().size()).append(" file(s)").append('\n');
        }
        if (!decisionNotes.isEmpty()) {
            sb.append("decisions to take again (their evidence changed): ").append(String.join("; ", decisionNotes)).append('\n');
        }
        if (!ambiguous.isEmpty()) {
            sb.append("not linked, more than one service fits: ").append(String.join("; ", ambiguous)).append('\n');
        }
        return sb.toString();
    }

    /** Nodes whose id or attributes contain {@code text} (case-insensitive), optionally of one kind. */
    public String find(String text, String kind, int limit) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        List<String> hits = new ArrayList<>();
        int total = 0;
        for (var e : nodes.entrySet()) {
            Fact<Node> n = e.getValue().getFirst();
            if (kind != null && !kind.isBlank() && !n.fact().kind().equals(kind)) {
                continue;
            }
            boolean match = e.getKey().toLowerCase(Locale.ROOT).contains(t)
                    || e.getValue().stream().anyMatch(x -> x.fact().attrs().toString().toLowerCase(Locale.ROOT).contains(t));
            if (match) {
                total++;
                if (hits.size() < limit) {
                    hits.add("- " + e.getKey() + "  (" + cite(n.fact().evidence(), n.header()) + ")");
                }
            }
        }
        if (total == 0) {
            return "No node matches '" + text + "'" + (kind == null || kind.isBlank() ? "" : " of kind " + kind) + ".";
        }
        return total + " nodes" + (total > hits.size() ? " (first " + hits.size() + ")" : "") + ":\n" + String.join("\n", hits);
    }

    /** A node: its attributes and evidence (per environment and file), and its edges. */
    public String describe(String id, int limit) {
        List<Fact<Node>> ns = nodes.getOrDefault(id, List.of());
        List<Fact<Edge>> o = out.getOrDefault(id, List.of());
        List<Fact<Edge>> i = in.getOrDefault(id, List.of());
        if (ns.isEmpty() && o.isEmpty() && i.isEmpty()) {
            return "No node " + id + ". " + find(id.contains(":") ? id.substring(id.lastIndexOf(':') + 1) : id, null, 5);
        }
        StringBuilder sb = new StringBuilder(id).append('\n');
        String name = id.substring(id.lastIndexOf(':') + 1);
        String reads = knowledge.read(name.contains(".") && id.startsWith("table:") ? name.substring(name.lastIndexOf('.') + 1) : name);
        if (reads != null) {
            sb.append("  reads as: ").append(reads).append('\n');
        }
        if (ns.isEmpty()) {
            sb.append("  (no node fact: only edges name it)\n");
        }
        Knowledge.Summary summary = knowledge.summaries().get(id);   // #168
        if (summary != null) {
            sb.append("  summary").append(summary.proposed() ? " (proposed by " + summary.by() + ", not approved yet)" : "").append(": ")
                    .append(summary.text()).append(stale(summary.evidence()) ? " [STALE: its evidence changed]" : "").append('\n');
        }
        knowledge.flows().values().stream().filter(f -> f.nodes().contains(id)).forEach(f -> sb.append("  part of flow \"").append(f.name())
                .append("\"").append(f.proposed() ? " (proposed)" : "").append('\n'));
        for (Fact<Node> n : ns) {
            sb.append("  ").append(n.fact().env() == null ? "" : "[" + n.fact().env() + "] ")
                    .append(n.fact().attrs().isEmpty() ? "" : n.fact().attrs() + " ")
                    .append("(").append(cite(n.fact().evidence(), n.header())).append(")\n");
        }
        appendEdges(sb, "out", o, true, limit);
        appendEdges(sb, "in", i, false, limit);
        return sb.toString();
    }

    /** The edges of a node: out (what it does), in (what points at it) or both, optionally of some kinds. */
    public String neighbors(String id, String direction, Collection<String> kinds, int limit) {
        String d = direction == null ? "both" : direction;
        StringBuilder sb = new StringBuilder(id).append('\n');
        if (!d.equals("in")) {
            appendEdges(sb, "out", filter(out.getOrDefault(id, List.of()), kinds), true, limit);
        }
        if (!d.equals("out")) {
            appendEdges(sb, "in", filter(in.getOrDefault(id, List.of()), kinds), false, limit);
        }
        return sb.toString();
    }

    /** The shortest chain of edges from one node to another, following edges forward (then either way, if none). */
    public String path(String from, String to, int maxDepth) {
        List<Fact<Edge>> p = bfs(from, to, maxDepth, true);
        boolean directed = true;
        if (p == null) {
            p = bfs(from, to, maxDepth, false);
            directed = false;
        }
        if (p == null) {
            return "No chain of at most " + maxDepth + " edges between " + from + " and " + to + ".";
        }
        StringBuilder sb = new StringBuilder(directed ? "" : "No forward chain; one that follows some edges backwards:\n");
        for (Fact<Edge> e : p) {
            sb.append("- ").append(e.fact().from()).append(" -").append(e.fact().kind()).append("-> ").append(e.fact().to())
                    .append(env(e.fact().env())).append("  (").append(cite(e.fact().evidence(), e.header())).append(")\n");
        }
        return sb.toString();
    }

    /** Who writes (or reads) a table: {@code schema.name}, or a bare name matching any schema. */
    public String tableAccess(String table, boolean writes, int limit) {
        String kind = writes ? "writes" : "reads";
        List<String> ids = tableIds(table);
        if (ids.isEmpty()) {
            return "No table " + table + " in the facts. " + find(table, "entity", 5);
        }
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            List<Fact<Edge>> es = filter(in.getOrDefault(id, List.of()), List.of(kind));
            sb.append(id).append(": ").append(es.size()).append(es.size() == 1 ? " place " : " places ").append(kind).append(" it\n");
            es.stream().sorted(Comparator.comparing((Fact<Edge> e) -> e.fact().from())).limit(limit).forEach(e -> sb.append("- ")
                    .append(e.fact().from()).append(attrs(e.fact().attrs())).append(env(e.fact().env())).append("  (")
                    .append(cite(e.fact().evidence(), e.header())).append(")\n"));
            List<Fact<Edge>> maps = filter(in.getOrDefault(id, List.of()), List.of("maps"));
            maps.forEach(e -> sb.append("  entity: ").append(e.fact().from()).append('\n'));
        }
        return sb.toString();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private List<String> tableIds(String table) {
        String t = table.startsWith("table:") ? table.substring("table:".length()) : table;
        String lower = t.toLowerCase(Locale.ROOT);
        Set<String> ids = new LinkedHashSet<>();
        Stream.concat(nodes.keySet().stream(), in.keySet().stream()).filter(id -> id.startsWith("table:")).forEach(id -> {
            String name = id.substring("table:".length()).toLowerCase(Locale.ROOT);
            if (name.equals(lower) || name.endsWith("." + lower)) {
                ids.add(id);
            }
        });
        return List.copyOf(ids);
    }

    private List<Fact<Edge>> bfs(String from, String to, int maxDepth, boolean directed) {
        Map<String, Fact<Edge>> via = new LinkedHashMap<>();
        Map<String, Integer> depth = new HashMap<>(Map.of(from, 0));
        Deque<String> queue = new ArrayDeque<>(List.of(from));
        while (!queue.isEmpty()) {
            String n = queue.poll();
            if (n.equals(to)) {
                List<Fact<Edge>> p = new ArrayList<>();
                for (String c = to; !c.equals(from); ) {
                    Fact<Edge> e = via.get(c);
                    p.addFirst(e);
                    c = e.fact().to().equals(c) ? e.fact().from() : e.fact().to();
                }
                return p;
            }
            if (depth.get(n) >= maxDepth) {
                continue;
            }
            List<Fact<Edge>> next = new ArrayList<>(out.getOrDefault(n, List.of()));
            if (!directed) {
                next.addAll(in.getOrDefault(n, List.of()));
            }
            for (Fact<Edge> e : next) {
                String m = e.fact().from().equals(n) ? e.fact().to() : e.fact().from();
                if (!depth.containsKey(m)) {
                    depth.put(m, depth.get(n) + 1);
                    via.put(m, e);
                    queue.add(m);
                }
            }
        }
        return null;
    }

    private static List<Fact<Edge>> filter(List<Fact<Edge>> es, Collection<String> kinds) {
        return kinds == null || kinds.isEmpty() ? es : es.stream().filter(e -> kinds.contains(e.fact().kind())).toList();
    }

    private static void appendEdges(StringBuilder sb, String label, List<Fact<Edge>> es, boolean outgoing, int limit) {
        if (es.isEmpty()) {
            return;
        }
        sb.append("  ").append(label).append(" (").append(es.size()).append("):\n");
        es.stream().sorted(Comparator.comparing((Fact<Edge> e) -> e.fact().kind()).thenComparing(e -> outgoing ? e.fact().to() : e.fact().from()))
                .limit(limit).forEach(e -> sb.append("  - ").append(outgoing ? "-" + e.fact().kind() + "-> " + e.fact().to()
                        : e.fact().from() + " -" + e.fact().kind() + "->").append(attrs(e.fact().attrs())).append(env(e.fact().env()))
                        .append(e.fact().provenance() == null || e.fact().provenance().json().equals("extracted") ? ""
                                : " [" + e.fact().provenance().json() + (e.fact().via() == null ? "" : " via " + e.fact().via()) + "]")
                        .append("  (").append(cite(e.fact().evidence(), e.header())).append(")\n"));
        if (es.size() > limit) {
            sb.append("  … ").append(es.size() - limit).append(" more\n");
        }
    }

    private static String attrs(Map<String, Object> attrs) {
        return attrs == null || attrs.isEmpty() ? "" : " " + attrs;
    }

    private static String env(String env) {
        return env == null ? "" : " [" + env + "]";
    }

    /** {@code repo/file:line@commit}, the repo and commit from the file's header when the evidence leaves them out. */
    static String cite(Evidence e, Header h) {
        if (e == null) {
            return "no evidence";
        }
        String repo = e.repo() != null ? e.repo() : h == null ? null : h.repo();
        String commit = e.commit() != null ? e.commit() : h == null ? null : h.commit();
        return (repo == null ? "" : repo + "/") + (e.root() == null ? "" : e.root() + "/") + e.file()
                + (e.line() == null ? "" : ":" + e.line()) + (commit == null ? "" : "@" + commit.substring(0, Math.min(7, commit.length())));
    }
}
