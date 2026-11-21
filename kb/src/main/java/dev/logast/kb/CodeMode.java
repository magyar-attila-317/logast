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
    static final int MAX_RESULT = 40_000;
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
                declare function runs(jobId: string, day: string, env?: string, zone?: string): string[];
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
        return sb.toString();
    }

    /** The program's result as JSON, or what went wrong with a hint (an error the agent can fix and run again). */
    public static Result run(KnowledgeBase kb, String code) {
        Set<String> kinds = kb.kindCounts().keySet();
        try (Engine engine = Engine.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build();
             Context ctx = Context.newBuilder("js").engine(engine)
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
            js.putMember("__runs", (ProxyExecutable) a -> json(runs(kb, str(a, 0), str(a, 1), str(a, 2), str(a, 3))));
            js.putMember("__lineage", (ProxyExecutable) a -> Lineage.lineage(kb, str(a, 0), !"up".equalsIgnoreCase(str(a, 1)),
                    a.length > 2 && a[2].fitsInInt() ? a[2].asInt() : 6, 200));
            ctx.eval(Source.create("js", """
                    for (const g of ["Java", "Packages", "java", "javax", "com", "org", "edu", "net", "Polyglot", "load", "print"]) {
                      try { delete globalThis[g]; } catch (e) { globalThis[g] = undefined; }
                    }
                    const nodes = (kind, where) => { const all = JSON.parse(__nodes(kind ?? "")); return where ? all.filter(where) : all; };
                    const node = id => JSON.parse(__node(id));
                    const out = (id, kind) => JSON.parse(__out(id, kind ?? ""));
                    const into = (id, kind) => JSON.parse(__into(id, kind ?? ""));
                    const find = (text, kind) => JSON.parse(__find(text, kind ?? ""));
                    const runs = (id, day, env, zone) => JSON.parse(__runs(id, day, env ?? "", zone ?? ""));
                    const lineage = (id, direction, depth) => __lineage(id, direction ?? "down", depth ?? 6);
                    """));
            var timer = WATCHDOG.schedule(() -> ctx.close(true), TIMEOUT_MS, TimeUnit.MILLISECONDS);
            try {
                Value v = ctx.eval(Source.create("js", "JSON.stringify((() => {\n" + code + "\n})()) ?? 'null'"));
                String out = v.asString();
                if (out.length() > MAX_RESULT) {
                    return new Result(null, "the result is " + out.length() + " characters, more than " + MAX_RESULT
                            + ": filter, map or count in the program and return less");
                }
                return new Result(out, null);
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

    /** A program's JSON result, or why there is none. */
    public record Result(String json, String error) {
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
        return kb.nodes().stream().filter(f -> f.fact().id().equals(id)).map(CodeMode::node).toList();
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

    private static List<String> runs(KnowledgeBase kb, String id, String dayText, String env, String zoneText) {
        ZoneId zone = ZoneId.of(zoneText.isBlank() ? "UTC" : zoneText);
        LocalDate day = Schedules.day(dayText, LocalDate.now(zone));
        Set<String> out = new LinkedHashSet<>();
        for (var f : kb.nodes()) {
            if (!f.fact().id().equals(id) || !env.isBlank() && f.fact().env() != null && !f.fact().env().equals(env)) {
                continue;
            }
            for (Schedules.Schedule s : Schedules.all(f, env.isBlank() ? null : env)) {
                if (s.cron() != null) {
                    s.cron().on(day, s.zone(zone)).forEach(t -> out.add(t.withZoneSameInstant(zone).toLocalTime() + s.triggerLabel()));
                } else if (s.every() != null) {
                    out.add(s.every());
                }
            }
        }
        return new ArrayList<>(new TreeSet<>(out));
    }
}
