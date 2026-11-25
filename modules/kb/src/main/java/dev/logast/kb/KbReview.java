package dev.logast.kb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.logast.facts.Provenance;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * #169: a local page (loopback only) to decide what waits for a person: inferred links with no decision or a stale
 * one, workloads two services claim, and the LLM's proposals. Decisions go to {@code knowledge.decisions.yml}, a file
 * logast manages next to the people's {@code knowledge.yml}, loaded like it.
 */
public final class KbReview {
    public static final String FILE = "knowledge.decisions.yml";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER).enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));
    private static final String HEADER = "# Written by logast mcp review: decisions taken on the review page. Loaded like knowledge.yml;\n"
            + "# edit knowledge.yml for your own entries. This file is rewritten on each decision.\n";

    /** Something to decide: an inferred {@code link}, an {@code ambiguous} workload, a proposed {@code flow} or {@code summary}. */
    public record Item(String type, String key, String env, String text, String detail, String evidence) {
    }

    private final Supplier<KnowledgeBase> kb;
    private final Path decisions;
    private final Path proposed;
    private final String by;
    private HttpServer server;

    /**
     * @param kb        loads the facts afresh (each request sees the last decision)
     * @param decisions knowledge.decisions.yml to write to
     * @param proposed  knowledge.proposed.yml whose entries approving or rejecting removes
     * @param by        who decides
     */
    public KbReview(Supplier<KnowledgeBase> kb, Path decisions, Path proposed, String by) {
        this.kb = kb;
        this.decisions = decisions;
        this.proposed = proposed;
        this.by = by;
    }

    public static List<Item> items(KnowledgeBase kb) {
        List<Item> out = new ArrayList<>();
        for (var e : kb.allEdges()) {
            if (e.fact().provenance() != Provenance.INFERRED) {
                continue;
            }
            Object d = e.fact().attrs().get("decision");
            if (d != null && !d.toString().startsWith("stale")) {
                continue;
            }
            String link = e.fact().from() + " -" + e.fact().kind() + "-> " + e.fact().to();
            out.add(new Item("link", link, e.fact().env(), link, e.fact().via() + ", confidence " + e.fact().confidence()
                    + (d == null ? "" : "; " + d), KnowledgeBase.cite(e.fact().evidence(), e.header())));
        }
        for (String a : kb.ambiguous()) {
            out.add(new Item("ambiguous", a, null, a, "two services fit: decide with a link in knowledge.yml's services:", null));
        }
        kb.knowledge().flows().values().stream().filter(Knowledge.Flow::proposed).forEach(f -> out.add(new Item("flow", f.name(), null,
                f.name() + (f.description() == null ? "" : ": " + f.description()), String.join(" → ", f.nodes())
                + (kb.stale(f.evidence()) ? " [STALE]" : ""), String.join(", ", f.sources()))));
        kb.knowledge().summaries().values().stream().filter(Knowledge.Summary::proposed).forEach(s -> out.add(new Item("summary", s.node(), null,
                s.node() + ": " + s.text(), kb.stale(s.evidence()) ? "[STALE]" : "", String.join(", ", s.sources()))));
        return out;
    }

    /** Approve or reject one item; returns what happened. */
    public String decide(String type, String key, String env, boolean approve) throws IOException {
        KnowledgeBase k = kb.get();
        ObjectNode root = read(decisions);
        switch (type) {
            case "link" -> {
                Item item = items(k).stream().filter(i -> i.type().equals("link") && i.key().equals(key)
                        && java.util.Objects.equals(i.env(), env)).findFirst().orElse(null);
                if (item == null) {
                    return "no such link waiting: " + key;
                }
                ArrayNode list = root.has("decisions") && root.get("decisions").isArray() ? (ArrayNode) root.get("decisions") : root.putArray("decisions");
                ObjectNode d = list.addObject().put("link", key);
                if (env != null) {
                    d.put("env", env);
                }
                d.put("decision", approve ? "approve" : "reject").put("by", by).put("at", Instant.now().toString());
                if (item.evidence() != null) {
                    d.put("evidence", item.evidence());
                }
            }
            case "flow", "summary" -> {
                String section = type.equals("flow") ? "flows" : "summaries";
                ObjectNode prop = read(proposed);
                JsonNode entry = prop.path(section).path(key);
                if (entry.isMissingNode()) {
                    return "no such proposal: " + key;
                }
                if (approve) {
                    ObjectNode copy = entry.deepCopy();
                    copy.put("approvedBy", by).put("approvedAt", Instant.now().toString());
                    (root.has(section) && root.get(section).isObject() ? (ObjectNode) root.get(section) : root.putObject(section)).set(key, copy);
                }
                ((ObjectNode) prop.get(section)).remove(key);
                write(proposed, prop, "# Written by logast mcp (propose_flow, propose_summary): what the team's LLM proposes.\n");
            }
            default -> {
                return "only links and proposals are decided here";
            }
        }
        write(decisions, root, HEADER);
        return (approve ? "approved: " : "rejected: ") + key;
    }

    private static ObjectNode read(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return YAML.createObjectNode();
        }
        JsonNode n = YAML.readTree(file.toFile());
        return n != null && n.isObject() ? (ObjectNode) n : YAML.createObjectNode();
    }

    private static void write(Path file, ObjectNode root, String header) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, KbSchemas.stamp(file, header + YAML.writeValueAsString(root)));   // #321
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    // ---- the page -----------------------------------------------------------------------------------------------------

    /** Starts the page on loopback; returns its URL. */
    public String start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/", this::handle);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange x) throws IOException {
        try {
            String path = x.getRequestURI().getPath();
            if (x.getRequestMethod().equals("GET") && path.equals("/")) {
                send(x, 200, "text/html; charset=utf-8", PAGE);
            } else if (x.getRequestMethod().equals("GET") && path.equals("/items")) {
                send(x, 200, "application/json", JSON.writeValueAsString(items(kb.get())));
            } else if (x.getRequestMethod().equals("POST") && path.equals("/decide")) {
                JsonNode b = JSON.readTree(x.getRequestBody());
                String r = decide(b.path("type").asText(), b.path("key").asText(), b.path("env").isTextual() ? b.path("env").asText() : null,
                        b.path("decision").asText().equals("approve"));
                send(x, 200, "application/json", JSON.writeValueAsString(java.util.Map.of("result", r)));
            } else {
                send(x, 404, "text/plain", "not found");
            }
        } catch (IOException | RuntimeException e) {
            send(x, 500, "text/plain", String.valueOf(e.getMessage()));
        }
    }

    private static void send(HttpExchange x, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", type);
        x.sendResponseHeaders(status, bytes.length);
        try (OutputStream o = x.getResponseBody()) {
            o.write(bytes);
        }
    }

    private static final String PAGE = """
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Knowledge review</title>
            <style>
              :root { --bg:#fafaf9; --fg:#1c1917; --muted:#57534e; --line:#e7e5e4; --ok:#166534; --no:#991b1b; }
              @media (prefers-color-scheme: dark) { :root { --bg:#1c1917; --fg:#f5f5f4; --muted:#a8a29e; --line:#44403c; --ok:#4ade80; --no:#f87171; } }
              body { background:var(--bg); color:var(--fg); font:14px/1.45 system-ui, sans-serif; margin:0; padding:16px; }
              h1 { font-size:20px; margin:0 0 4px; } p.sub { color:var(--muted); margin:0 0 16px; }
              h2 { font-size:15px; margin:24px 0 8px; }
              .item { border:1px solid var(--line); border-radius:8px; padding:10px 12px; margin:8px 0; overflow-wrap:anywhere; }
              .text { font-family:ui-monospace, monospace; font-size:13px; } .detail, .ev { color:var(--muted); font-size:12px; margin-top:4px; }
              button { margin:8px 8px 0 0; padding:4px 12px; border-radius:6px; border:1px solid var(--line); background:transparent; color:var(--fg); cursor:pointer; }
              button.ok { border-color:var(--ok); color:var(--ok); } button.no { border-color:var(--no); color:var(--no); }
              .done { opacity:.45; }
            </style></head><body>
            <h1>Knowledge review</h1>
            <p class="sub">Inferred links and proposals waiting for a person. Decisions go to knowledge.decisions.yml.</p>
            <div id="list">Loading…</div>
            <script>
            const titles = {link: "Inferred links", ambiguous: "Not linked: more than one service fits", flow: "Proposed flows", summary: "Proposed summaries"};
            function el(tag, cls, text) { const e = document.createElement(tag); if (cls) e.className = cls; if (text) e.textContent = text; return e; }
            async function decide(item, decision, box) {
              const r = await fetch("decide", {method: "POST", headers: {"Content-Type": "application/json"},
                body: JSON.stringify({type: item.type, key: item.key, env: item.env, decision})});
              const j = await r.json();
              box.classList.add("done");
              box.querySelectorAll("button").forEach(b => b.remove());
              box.appendChild(el("div", "detail", j.result));
            }
            async function load() {
              const items = await (await fetch("items")).json();
              const list = document.getElementById("list");
              list.textContent = items.length ? "" : "Nothing waits for a decision.";
              for (const type of Object.keys(titles)) {
                const of = items.filter(i => i.type === type);
                if (!of.length) continue;
                list.appendChild(el("h2", null, titles[type] + " (" + of.length + ")"));
                for (const i of of) {
                  const box = el("div", "item");
                  box.appendChild(el("div", "text", i.text + (i.env ? "  [" + i.env + "]" : "")));
                  if (i.detail) box.appendChild(el("div", "detail", i.detail));
                  if (i.evidence) box.appendChild(el("div", "ev", i.evidence));
                  if (type !== "ambiguous") {
                    const ok = el("button", "ok", "Approve"); ok.onclick = () => decide(i, "approve", box);
                    const no = el("button", "no", "Reject"); no.onclick = () => decide(i, "reject", box);
                    box.append(ok, no);
                  }
                  list.appendChild(box);
                }
              }
            }
            load();
            </script></body></html>
            """;
}
