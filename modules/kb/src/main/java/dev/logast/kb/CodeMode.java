package dev.logast.kb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.logast.facts.Edge;
import dev.logast.facts.Node;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.polyglot.proxy.ProxyExecutable;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * #201: code mode. Instead of twenty tools, an agent reads the facts API ({@link #api}) and sends one read-only
 * JavaScript program ({@link #run}); only its result comes back. The program runs in GraalJS with no host access, no
 * I/O, a statement limit, a time limit and a fresh context per call; the facts reach it only through functions that
 * return JSON.
 */
public final class CodeMode {
    static final long TIMEOUT_MS = 5_000;
    static final long STATEMENTS = 50_000_000;
    static final int MAX_RESULT = 20_000;   // #229
    static final int MAX_NOTES = 20;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "logast-code-mode-watchdog");
        t.setDaemon(true);
        return t;
    });

    private CodeMode() {
    }

    /** The facts API as a TypeScript declaration, with what is loaded now and worked examples. */
    public static String api(KnowledgeBase kb, String kind) {
        StringBuilder sb = new StringBuilder("""
                // The facts of many repos (code, deployment, infrastructure, CI), read-only. Write a JavaScript program
                // for the query tool: plain ES2022, its last `return` value comes back as JSON (keep it small: filter,
                // map and count here, not in the answer). Cite each fact's `source`.
                interface Node { id: string /* "<kind>:<name>" */; kind: string; env?: string; attrs: Record<string, any>; source: string /* repo/file:line */ }
                interface Edge { from: string; kind: string; to: string; env?: string; attrs: Record<string, any>; source: string }

                /** Nodes of a kind (all kinds: ""), optionally filtered: nodes("job", n => n.attrs.entrypoint === "quartz") */
                declare function nodes(kind: string, where?: (n: Node) => boolean): Node[];
                /** One node (each environment's version of it: nodes with the same id and different env) */
                declare function node(id: string): Node[];
                /** Edges out of / into a node, optionally of one kind: out("service:billing", "calls"), into("table:x.y", "writes") */
                declare function out(id: string, kind?: string): Edge[];
                declare function into(id: string, kind?: string): Edge[];
                /** Nodes whose id or attributes contain a text (case-insensitive), optionally of one kind */
                declare function find(text: string, kind?: string): Node[];
                /** The times a job runs on a day ("monday", "today", "2026-11-23") in an environment, in a zone (default UTC) */
                /** "≥ 01:00 … after X": a later step of a chain starts after the job before it ends, not at the clock time */
                declare function runs(jobId: string, day: string, env?: string, zone?: string): string[];
                /** How a job is scheduled: each cron (and trigger), or what runs it, or "not known: …". An empty runs() is not
                    "never": check schedule() for the jobs it returns nothing for, and say which schedules are not known (the
                    query result lists them under "Not known"). */
                declare function schedule(jobId: string, env?: string): string[];
                /** What follows from a node (down: calls, messages, writes, readers, next steps) or leads to it (up), as text */
                declare function lineage(id: string, direction?: "down" | "up", depth?: number): string;
                """);
        Map<String, Integer> kinds = kb.kindCounts();
        sb.append("\n// Loaded now: ").append(kb.repoCount()).append(" repos; ")
                .append(String.join(", ", kinds.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue())
                        .map(e -> e.getValue() + " " + e.getKey()).toList()));
        if (!kb.environments().isEmpty()) {
            sb.append("; environments: ").append(String.join(", ", kb.environments()));
        }
        Map<String, Set<String>> edgeKinds = new TreeMap<>();
        kb.allEdges().forEach(e -> edgeKinds.computeIfAbsent(e.fact().kind(), x -> new TreeSet<>()));
        sb.append("\n// Edge kinds: ").append(String.join(", ", edgeKinds.keySet()));
        sb.append("\n// What runs: job (attrs.entrypoint: scheduled, quartz, startup = one per profile a container starts with, "
                + "cronjob, pipeline), listener (messages, events), endpoint (HTTP). Raw values to join on: property "
                + "(a configuration key: value, byProfile), constant, enum-constant (values), row (a table's row). "
                + "Filter and count in the program; return small results.");   // #215, #201
        sb.append("\n// Attributes seen per kind").append(kind == null || kind.isBlank() ? "" : " (" + kind + ")").append(":");
        Map<String, Set<String>> attrs = new TreeMap<>();
        for (var f : kb.nodes()) {
            if (kind == null || kind.isBlank() || f.fact().kind().equals(kind)) {
                Set<String> s = attrs.computeIfAbsent(f.fact().kind(), x -> new TreeSet<>());
                if (f.fact().attrs() != null) {
                    s.addAll(f.fact().attrs().keySet());
                }
            }
        }
        attrs.forEach((k, v) -> sb.append("\n//   ").append(k).append(": ").append(String.join(", ", v)));
        sb.append("""


                // Examples
                // 1. Quartz jobs and their crons:
                //    return nodes("job", j => j.attrs.entrypoint === "quartz").map(j => ({ id: j.id, cron: j.attrs.cron, zone: j.attrs.zone, source: j.source }));
                // 2. Who writes a table, and who reads it:
                //    const t = "table:billing.invoice"; return { writers: into(t, "writes").map(e => e.from), readers: into(t, "reads").map(e => e.from) };
                // 3. Jobs that run on Monday in prod and write a table another service reads:
                //    const svc = id => id.split(":")[1];
                //    return nodes("job").filter(j => runs(j.id, "monday", "prod").length).flatMap(j => out(j.id, "writes").map(w => ({
                //      job: j.id, at: runs(j.id, "monday", "prod")[0], table: w.to,
                //      readBy: into(w.to, "reads").map(r => r.from).filter(f => svc(f) !== svc(j.id)) }))).filter(x => x.readBy.length);
                // 4. A service's deployments per environment (Helm), with image and replicas:
                //    return nodes("deployment", d => d.id.includes("billing")).map(d => ({ env: d.env, image: d.attrs.image, replicas: d.attrs.replicas }));
                """);
        sb.append("// The code's structure (export --code-level): class (superChain, interfaces, methodCount, inheritedMethodCount, "
                + "reachedByCount), method; edges calls (method to method, per call site), extends, implements. "
                + "Every call node says what protects it: timeout, retry, circuitBreaker, bulkhead (yes / no / not known, "
                + "with <name>From).\n");
        int n = 5;
        for (String[] e : CODE_EXAMPLES) {
            sb.append("// ").append(n++).append(". ").append(e[0]).append(":\n");
            e[1].lines().forEach(l -> sb.append("//    ").append(l).append('\n'));
        }
        return sb.toString();
    }

    /** #255: examples over the code's structure, {title, program}; CodeModeTest runs each as written. */
    static final List<String[]> CODE_EXAMPLES = List.of(
            new String[]{"Deep inheritance, library types included (superChain ends at java.lang.Object)",
                    "return nodes(\"class\", c => c.attrs.superChain.length > 3).map(c => ({ id: c.id, depth: c.attrs.superChain.length,"
                            + " chain: c.attrs.superChain, complete: c.attrs.superChainComplete !== false, source: c.source }));"},
            new String[]{"Large interfaces, counting what they inherit",
                    "return nodes(\"class\", c => c.attrs.kind === \"interface\").map(c => ({ id: c.id,"
                            + " methods: c.attrs.methodCount + (c.attrs.inheritedMethodCount || 0), source: c.source }))"
                            + ".filter(x => x.methods > 10).sort((a, b) => b.methods - a.methods);"},
            new String[]{"What a method's call sites really dispatch to (ambiguous: several implementations)",
                    "const m = nodes(\"method\", x => x.id.endsWith(\"#stock(java.lang.String)\"))[0];\n"
                            + "return out(m.id, \"calls\").map(e => ({ to: e.to, ambiguous: !!e.attrs.ambiguous, site: e.source }));"},
            new String[]{"Outgoing calls without a timeout, ranked by how many entrypoints reach the class making them (joined by file)",
                    "const file = s => s.split(\":\")[0];\n"
                            + "const reach = {}; nodes(\"class\").forEach(c => reach[file(c.source)] = Math.max(reach[file(c.source)] || 0, c.attrs.reachedByCount));\n"
                            + "return nodes(\"call\", c => c.attrs.timeout === \"no\").map(c => ({ call: c.id, builtAt: c.attrs.timeoutFrom,"
                            + " reachedBy: reach[file(c.source)] || 0 })).sort((a, b) => b.reachedBy - a.reachedBy);"});

    private static volatile Engine engine;

    /** #204: one engine for the server's life: each query still gets its own context, but parsing and warm-up are shared. */
    private static Engine engine() {
        Engine e = engine;
        if (e == null) {
            synchronized (CodeMode.class) {
                e = engine;
                if (e == null) {
                    e = Engine.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build();
                    engine = e;
                }
            }
        }
        return e;
    }

    /** The program's result as JSON, or what went wrong with a hint (an error the agent can fix and run again). */
    public static Result run(KnowledgeBase kb, String code) {
        Set<String> kinds = kb.kindCounts().keySet();
        Set<String> notes = new LinkedHashSet<>();   // #228: what the functions it called could not know
        try (Context ctx = Context.newBuilder("js").engine(engine())
                     .allowHostAccess(HostAccess.NONE).allowHostClassLookup(c -> false).allowIO(IOAccess.NONE)
                     .allowCreateThread(false).allowCreateProcess(false).allowNativeAccess(false)
                     .allowPolyglotAccess(PolyglotAccess.NONE).allowEnvironmentAccess(org.graalvm.polyglot.EnvironmentAccess.NONE)
                     .resourceLimits(ResourceLimits.newBuilder().statementLimit(STATEMENTS, null).build())
                     .out(java.io.OutputStream.nullOutputStream()).err(java.io.OutputStream.nullOutputStream())   // stdout is MCP's
                     .in(java.io.InputStream.nullInputStream())
                     .option("js.strict", "true").option("js.print", "false").option("js.load", "false")
                     .option("js.graal-builtin", "false")
                     .option("js.console", "false").build()) {
            Value js = ctx.getBindings("js");
            js.putMember("__nodes", (ProxyExecutable) a -> json(nodes(kb, str(a, 0), kinds)));
            js.putMember("__node", (ProxyExecutable) a -> json(node(kb, str(a, 0))));
            js.putMember("__out", (ProxyExecutable) a -> json(edges(kb.outgoing(str(a, 0)), str(a, 1))));
            js.putMember("__into", (ProxyExecutable) a -> json(edges(kb.incoming(str(a, 0)), str(a, 1))));
            js.putMember("__find", (ProxyExecutable) a -> json(find(kb, str(a, 0), str(a, 1))));
            js.putMember("__runs", (ProxyExecutable) a -> json(runs(kb, str(a, 0), str(a, 1), str(a, 2), str(a, 3), notes)));
            js.putMember("__schedule", (ProxyExecutable) a -> json(schedule(kb, str(a, 0), str(a, 1), notes)));
            js.putMember("__lineage", (ProxyExecutable) a -> Lineage.lineage(kb, str(a, 0), !"up".equalsIgnoreCase(str(a, 1)),
                    a.length > 2 && a[2].fitsInInt() ? a[2].asInt() : 6, 200));
            ctx.eval(Source.create("js", """
                    for (const g of ["Java", "Packages", "java", "javax", "com", "org", "edu", "net", "Polyglot", "load", "print"]) {
                      try { delete globalThis[g]; } catch (e) { globalThis[g] = undefined; }
                    }
                    const __byKind = new Map();   // each kind's nodes once per query
                    const nodes = (kind, where) => {
                      const k = kind ?? "";
                      if (!__byKind.has(k)) __byKind.set(k, JSON.parse(__nodes(k)));
                      const all = __byKind.get(k);
                      return where ? all.filter(where) : all.slice();
                    };
                    const node = id => JSON.parse(__node(id));
                    const out = (id, kind) => JSON.parse(__out(id, kind ?? ""));
                    const into = (id, kind) => JSON.parse(__into(id, kind ?? ""));
                    const find = (text, kind) => JSON.parse(__find(text, kind ?? ""));
                    const runs = (id, day, env, zone) => JSON.parse(__runs(id, day, env ?? "", zone ?? ""));
                    const schedule = (id, env) => JSON.parse(__schedule(id, env ?? ""));
                    const lineage = (id, direction, depth) => __lineage(id, direction ?? "down", depth ?? 6);
                    """));
            var timer = WATCHDOG.schedule(() -> ctx.close(true), TIMEOUT_MS, TimeUnit.MILLISECONDS);
            try {
                Value v = ctx.eval(Source.create("js", "JSON.stringify((() => {\n" + code + "\n})()) ?? 'null'"));
                String out = v.asString();
                if (out.length() > MAX_RESULT) {
                    return new Result(null, "the result is " + out.length() + " characters, more than " + MAX_RESULT
                            + shape(out) + ": return only the fields the answer needs, filter, or count in the program", List.of());
                }
                return new Result(out, null, List.copyOf(notes));
            } finally {
                timer.cancel(false);
            }
        } catch (PolyglotException e) {
            if (e.isCancelled() || e.isResourceExhausted()) {
                return new Result(null, "stopped: the program ran longer than " + TIMEOUT_MS / 1000 + " s or "
                        + STATEMENTS / 1_000_000 + "M steps (an endless loop? walking every node many times? filter first)");
            }
            return new Result(null, (e.isSyntaxError() ? "syntax error: " : "error: ") + e.getMessage()
                    + (e.getSourceLocation() != null ? " (line " + Math.max(1, e.getSourceLocation().getStartLine() - 1) + ")" : ""));
        } catch (IllegalArgumentException e) {
            return new Result(null, e.getMessage());
        }
    }

    /**
     * A program's JSON result, or why there is none; {@code notes}: what the functions it called could not know (#228),
     * to be said in the answer.
     */
    public record Result(String json, String error, List<String> notes) {
        public Result(String json, String error) {
            this(json, error, List.of());
        }

        /** The notes as text for the agent, at most {@link #MAX_NOTES} lines; empty when there are none. */
        public String notesText() {
            if (notes.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder("Not known (say so in the answer; an empty runs() for these is not \"never\"):");
            notes.stream().limit(MAX_NOTES).forEach(n -> sb.append("\n- ").append(n));
            if (notes.size() > MAX_NOTES) {
                sb.append("\n- … and ").append(notes.size() - MAX_NOTES).append(" more");
            }
            return sb.toString();
        }
    }

    /** #229: what makes a result large: a list's length and its heaviest fields, an object's heaviest keys. */
    static String shape(String json) {
        try {
            com.fasterxml.jackson.databind.JsonNode n = JSON.readTree(json);
            Map<String, Long> size = new java.util.HashMap<>();
            String what;
            if (n.isArray()) {
                for (var item : n) {
                    if (item.isObject()) {
                        item.properties().forEach(f -> size.merge(f.getKey(), (long) f.getValue().toString().length(), Long::sum));
                    }
                }
                what = " (a list of " + n.size() + " items";
                if (n.isEmpty() || size.isEmpty()) {
                    return what + ")";
                }
                int items = n.size();
                return what + "; per item: " + String.join(", ", size.entrySet().stream()
                        .sorted((x, y) -> Long.compare(y.getValue(), x.getValue())).limit(3)
                        .map(e -> e.getKey() + " ≈ " + e.getValue() / items + " characters").toList()) + ")";
            }
            if (n.isObject()) {
                n.properties().forEach(f -> size.put(f.getKey(), (long) f.getValue().toString().length()));
                return " (an object; " + String.join(", ", size.entrySet().stream()
                        .sorted((x, y) -> Long.compare(y.getValue(), x.getValue())).limit(3)
                        .map(e -> e.getKey() + " ≈ " + e.getValue() + " characters"
                                + (n.get(e.getKey()).isArray() ? " (" + n.get(e.getKey()).size() + " items)" : "")).toList()) + ")";
            }
            return "";
        } catch (java.io.IOException e) {
            return "";
        }
    }

    private static String str(Value[] a, int i) {
        return a.length > i && a[i] != null && !a[i].isNull() ? a[i].asString() : "";
    }

    private static String json(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ObjectNode node(KnowledgeBase.Fact<Node> f) {
        ObjectNode o = JSON.createObjectNode();
        o.put("id", f.fact().id()).put("kind", f.fact().kind());
        if (f.fact().env() != null) {
            o.put("env", f.fact().env());
        }
        o.set("attrs", JSON.valueToTree(f.fact().attrs() == null ? Map.of() : f.fact().attrs()));
        o.put("source", KnowledgeBase.cite(f.fact().evidence(), f.header()));
        return o;
    }

    private static List<ObjectNode> nodes(KnowledgeBase kb, String kind, Set<String> kinds) {
        if (!kind.isEmpty() && !kinds.contains(kind)) {
            String near = kinds.stream().filter(k -> k.startsWith(kind.replaceAll("s$", "")) || kind.startsWith(k)).findFirst().orElse(null);
            throw new IllegalArgumentException("no kind '" + kind + "'" + (near == null ? "" : "; did you mean '" + near + "'?")
                    + " kinds: " + String.join(", ", kinds));
        }
        List<ObjectNode> out = new ArrayList<>();
        for (var f : kb.nodes()) {
            if (kind.isEmpty() || f.fact().kind().equals(kind)) {
                out.add(node(f));
            }
        }
        return out;
    }

    private static List<ObjectNode> node(KnowledgeBase kb, String id) {
        return kb.node(id).stream().map(CodeMode::node).toList();
    }

    private static List<ObjectNode> edges(List<KnowledgeBase.Fact<Edge>> edges, String kind) {
        List<ObjectNode> out = new ArrayList<>();
        for (var e : edges) {
            if (!kind.isEmpty() && !e.fact().kind().equals(kind)) {
                continue;
            }
            ObjectNode o = JSON.createObjectNode();
            o.put("from", e.fact().from()).put("kind", e.fact().kind()).put("to", e.fact().to());
            if (e.fact().env() != null) {
                o.put("env", e.fact().env());
            }
            o.set("attrs", JSON.valueToTree(e.fact().attrs() == null ? Map.of() : e.fact().attrs()));
            o.put("source", KnowledgeBase.cite(e.fact().evidence(), e.header()));
            out.add(o);
        }
        return out;
    }

    private static List<ObjectNode> find(KnowledgeBase kb, String text, String kind) {
        String t = text.toLowerCase(Locale.ROOT);
        List<ObjectNode> out = new ArrayList<>();
        for (var f : kb.nodes()) {
            if ((kind.isEmpty() || f.fact().kind().equals(kind)) && (f.fact().id().toLowerCase(Locale.ROOT).contains(t)
                    || String.valueOf(f.fact().attrs()).toLowerCase(Locale.ROOT).contains(t))) {
                out.add(node(f));
            }
        }
        return out;
    }

    /** #218: how a job is scheduled, or that it is not known (and why). */
    private static List<String> schedule(KnowledgeBase kb, String id, String env, Set<String> notes) {
        List<String> out = new ArrayList<>();
        for (var f : kb.node(id)) {
            if (!env.isBlank() && f.fact().env() != null && !f.fact().env().equals(env)) {
                continue;
            }
            String where = f.fact().env() == null ? "" : " [" + f.fact().env() + "]";
            List<Schedules.Schedule> own = Schedules.all(f, env.isBlank() ? null : env);
            if (own.isEmpty()) {
                var by = Schedules.scheduledRunner(kb, id, env.isBlank() ? f.fact().env() : env);
                if (by == null) {
                    out.add("not known" + where + ": no schedule in the facts (started by another job, by hand, or by something logast does not read)");
                    notes.add(unknownNote(id, where));
                    continue;
                }
                for (Schedules.Schedule s : Schedules.all(by, env.isBlank() ? null : env)) {
                    out.add(describe(s) + where + " (run by " + by.fact().id() + ")");
                }
                continue;
            }
            own.forEach(s -> out.add(describe(s) + where));
            own.stream().filter(s -> s.problem() != null).forEach(s -> notes.add(id + where + ": " + s.problem()));
        }
        if (out.isEmpty()) {
            out.add("not known: no job " + id);
            notes.add(id + ": not in the facts");
        }
        return out;
    }

    private static String describe(Schedules.Schedule s) {
        return s.problem() != null ? "not known: " + s.problem() : s.every() != null ? s.every()
                : "cron " + s.cron().expression() + (s.zoneOfTrigger() != null ? " " + s.zoneOfTrigger()
                : s.job().fact().attrs().get("zone") != null ? " " + s.job().fact().attrs().get("zone") : "") + s.triggerLabel();
    }

    private static String unknownNote(String id, String where) {
        return id + where + ": schedule not known (no schedule in the facts and nothing that runs it)";
    }

    private static List<String> runs(KnowledgeBase kb, String id, String dayText, String env, String zoneText, Set<String> notes) {
        ZoneId zone = ZoneId.of(zoneText.isBlank() ? "UTC" : zoneText);
        LocalDate day = Schedules.day(dayText, LocalDate.now(zone));
        Set<String> out = new LinkedHashSet<>();
        if (kb.node(id).isEmpty()) {
            notes.add(id + ": not in the facts");
        }
        for (var f : kb.node(id)) {
            if (!env.isBlank() && f.fact().env() != null && !f.fact().env().equals(env)) {
                continue;
            }
            List<Schedules.Schedule> own = Schedules.all(f, env.isBlank() ? null : env);
            if (own.isEmpty()) {   // #218: run by a pipeline or a workload: its times
                var by = Schedules.scheduledRunner(kb, id, env.isBlank() ? f.fact().env() : env);
                own = by == null ? List.of() : Schedules.all(by, env.isBlank() ? null : env);
                if (by == null) {
                    notes.add(unknownNote(id, f.fact().env() == null ? "" : " [" + f.fact().env() + "]"));
                }
            }
            for (Schedules.Schedule s : own) {
                if (s.problem() != null) {
                    notes.add(id + (f.fact().env() == null ? "" : " [" + f.fact().env() + "]") + ": " + s.problem());
                }
                if (s.cron() != null) {
                    s.cron().on(day, s.zone(zone)).forEach(t -> out.add(s.at(String.valueOf(t.withZoneSameInstant(zone).toLocalTime())) + s.triggerLabel()));
                } else if (s.every() != null) {
                    out.add(s.every());
                }
            }
        }
        return new ArrayList<>(new TreeSet<>(out));
    }
}
