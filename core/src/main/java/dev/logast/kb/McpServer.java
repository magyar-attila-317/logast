package dev.logast.kb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * #156: an MCP server over stdio (JSON-RPC 2.0, one message per line) that answers questions over the facts of many
 * repos. It speaks the part of the protocol a tools-only server needs: {@code initialize}, {@code tools/list},
 * {@code tools/call}, {@code ping}; notifications get no answer. The facts are reloaded when a file changes, so a
 * refreshed fleet clone is picked up without restarting the client.
 */
public final class McpServer {
    static final ObjectMapper JSON = JsonMapper.builder().build();
    static final String PROTOCOL = "2025-06-18";

    private final Supplier<List<Path>> files;
    private final String version;
    private final PrintStream log;
    private final Path proposals;
    private KnowledgeBase kb;
    private String loadedStamp;

    /**
     * @param files   the facts files to serve (asked again on every call: new files are picked up)
     * @param version logast's version, for {@code serverInfo}
     * @param log     where diagnostics go (stderr: stdout carries the protocol only)
     */
    public McpServer(Supplier<List<Path>> files, String version, PrintStream log) {
        this(files, version, log, null);
    }

    /** @param proposals where propose_flow and propose_summary write (#168); null: read-only */
    public McpServer(Supplier<List<Path>> files, String version, PrintStream log, Path proposals) {
        this.files = files;
        this.version = version;
        this.log = log;
        this.proposals = proposals;
    }

    /** Serves until the input ends. */
    public void run(BufferedReader in, Writer out) throws IOException {
        for (String line; (line = in.readLine()) != null; ) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode reply = handle(line);
            if (reply != null) {
                out.write(JSON.writeValueAsString(reply));
                out.write('\n');
                out.flush();
            }
        }
    }

    /** One message in, the reply out (null for a notification). */
    JsonNode handle(String line) {
        JsonNode msg;
        try {
            msg = JSON.readTree(line);
        } catch (IOException e) {
            return error(null, -32700, "parse error: " + e.getMessage());
        }
        JsonNode id = msg.get("id");
        String method = msg.path("method").asText("");
        if (id == null || id.isNull()) {
            return null;   // a notification (notifications/initialized, cancelled, …): nothing to answer
        }
        try {
            return switch (method) {
                case "initialize" -> result(id, initialize(msg.path("params")));
                case "ping" -> result(id, JSON.createObjectNode());
                case "tools/list" -> result(id, tools());
                case "tools/call" -> result(id, call(msg.path("params")));
                default -> error(id, -32601, "method not found: " + method);
            };
        } catch (IllegalArgumentException e) {
            return error(id, -32602, e.getMessage());
        } catch (RuntimeException e) {
            log.println("logast mcp: " + e);
            return error(id, -32603, "internal error: " + e.getMessage());
        }
    }

    private ObjectNode initialize(JsonNode params) {
        ObjectNode r = JSON.createObjectNode();
        String asked = params.path("protocolVersion").asText("");
        r.put("protocolVersion", asked.isBlank() ? PROTOCOL : asked);
        r.putObject("capabilities").putObject("tools");
        r.putObject("serverInfo").put("name", "logast-kb").put("version", version);
        r.put("instructions", "Facts about code and deployment of many repos (docs/facts.md in logast). Node ids are "
                + "<kind>:<name>, e.g. service:shipping, endpoint:shipping:GET /shipments/{id}, table:billing.invoice, "
                + "job:shipping:ExportJob.run. Start with summary or find; cite the (repo/file:line) of each fact you use.");
        return r;
    }

    // ---- tools --------------------------------------------------------------------------------------------------------

    private record Tool(String name, String description, String[][] params, String[] required) {
    }

    private static final List<Tool> TOOLS = List.of(
            new Tool("summary", "What is loaded: facts files, repos, and nodes and edges by kind.", new String[0][], new String[0]),
            new Tool("find", "Nodes whose id or attributes contain a text (case-insensitive), optionally of one kind "
                    + "(service, endpoint, listener, job, call, bean, profile, property, table, entity, topic, library, …).",
                    new String[][]{{"text", "string", "the text to look for"}, {"kind", "string", "only nodes of this kind"},
                            {"limit", "integer", "at most this many (default 30)"}}, new String[]{"text"}),
            new Tool("describe", "A node: its attributes and evidence, and its edges in and out.",
                    new String[][]{{"id", "string", "the node id, e.g. endpoint:shipping:GET /shipments/{id}"},
                            {"limit", "integer", "edges per direction (default 50)"}}, new String[]{"id"}),
            new Tool("neighbors", "The edges of a node, by direction (out: what it does, in: what points at it) and edge kind "
                    + "(calls, reads, writes, publishes, consumes, runs, active-with, uses-property, has, maps, depends-on, …).",
                    new String[][]{{"id", "string", "the node id"}, {"direction", "string", "out, in or both (default both)"},
                            {"kinds", "string", "comma-separated edge kinds"}, {"limit", "integer", "edges per direction (default 50)"}},
                    new String[]{"id"}),
            new Tool("path", "The shortest chain of edges from one node to another, e.g. from a profile to the tables its job writes.",
                    new String[][]{{"from", "string", "the start node id"}, {"to", "string", "the end node id"},
                            {"maxDepth", "integer", "at most this many edges (default 8)"}}, new String[]{"from", "to"}),
            new Tool("runs_on", "Which jobs run on a day (in an environment), at which times, and the jobs that run after "
                    + "them in a pipeline; computed from the jobs' cron schedules.",
                    new String[][]{{"day", "string", "a weekday (monday) for the next such date, today, tomorrow, or an ISO date"},
                            {"env", "string", "the environment (dev, prod, …); default: every one"},
                            {"zone", "string", "the time zone of the times shown (default UTC)"}}, new String[]{"day"}),
            new Tool("next_runs", "When a job runs next, from its cron schedule.",
                    new String[][]{{"id", "string", "the job id, e.g. job:shipping:nightly-export"},
                            {"env", "string", "the environment; default: every one"}, {"count", "integer", "how many (default 5)"},
                            {"zone", "string", "the time zone of the times shown (default UTC)"}}, new String[]{"id"}),
            new Tool("lineage", "What follows from a node (downstream: the calls, topics and tables it writes to, who reads and "
                    + "consumes them, the next pipeline steps) or what leads to it (upstream), across services, as a tree with sources.",
                    new String[][]{{"id", "string", "the start node: an endpoint, job, listener, table or topic"},
                            {"direction", "string", "downstream (default) or upstream"}, {"depth", "integer", "default 6"},
                            {"limit", "integer", "lines (default 200)"}}, new String[]{"id"}),
            new Tool("risks", "What lineage shows as risky: database handoffs between scheduled jobs of different services with "
                    + "no pipeline step between them (the reader relies on timing), tables several services write, reads of "
                    + "another service's schema.", new String[][]{{"env", "string", "the environment; default: every one"}}, new String[0]),
            new Tool("secrets", "Key Vault secrets (Terraform, Helm) with who uses them: the workloads that mount or read them and "
                    + "the services whose code runs there; optionally only those expiring within some days.",
                    new String[][]{{"text", "string", "only secrets whose id contains this"}, {"env", "string", "the environment"},
                            {"expiringDays", "integer", "only secrets that expire within this many days"}}, new String[0]),
            new Tool("workflow_refs", "Per reusable GitHub Actions workflow, the refs its callers use (who is on an old one).",
                    new String[][]{{"text", "string", "only workflows whose id contains this"}}, new String[0]),
            new Tool("flows", "Business flows: the approved ones (knowledge.yml) and the ones proposed and not approved yet, with "
                    + "the nodes they consist of.", new String[][]{{"text", "string", "only flows whose name contains this"}}, new String[0]),
            new Tool("propose_flow", "Propose a business flow: a name for a chain of nodes (from lineage), what it does, and what that "
                    + "rests on. Written as a proposal a person approves; cite facts, glossary terms or pages.",
                    new String[][]{{"name", "string", "the flow's business name, e.g. month-end settlement"},
                            {"description", "string", "what the flow does, in business terms"},
                            {"nodes", "string", "comma-separated node ids, in order (from lineage)"},
                            {"sources", "string", "comma-separated: the fact ids, glossary terms and page URLs it rests on"}},
                    new String[]{"name", "nodes", "sources"}),
            new Tool("propose_summary", "Propose a summary of a node (a script, a job, a service): what it does, with what it rests on. "
                    + "Written as a proposal a person approves.",
                    new String[][]{{"id", "string", "the node id"}, {"text", "string", "the summary"},
                            {"sources", "string", "comma-separated: the files, facts or pages it rests on"}}, new String[]{"id", "text", "sources"}),
            new Tool("unknown_terms", "Words in the names of tables, columns, topics and properties that the glossary does not know "
                    + "yet, most frequent first, with examples: what to define next.",
                    new String[][]{{"limit", "integer", "default 40"}}, new String[0]),
            new Tool("term", "A business term from the company's glossary (knowledge.yml): its definition and source; an alias "
                    + "or an abbreviation finds its term.", new String[][]{{"name", "string", "the term, alias or abbreviation"}}, new String[]{"name"}),
            new Tool("read_name", "A technical name (a table, column, topic, key: ord_inv_ref, ordInvRef) as business words, through "
                    + "the company's abbreviations and aliases.", new String[][]{{"name", "string", "the name"}}, new String[]{"name"}),
            new Tool("writers_of", "Who writes a table: every entrypoint (endpoint, listener, job) and service that writes it.",
                    new String[][]{{"table", "string", "schema.name, or a name (any schema)"}, {"limit", "integer", "default 100"}},
                    new String[]{"table"}),
            new Tool("readers_of", "Who reads a table: every entrypoint (endpoint, listener, job) and service that reads it.",
                    new String[][]{{"table", "string", "schema.name, or a name (any schema)"}, {"limit", "integer", "default 100"}},
                    new String[]{"table"}));

    private static ObjectNode tools() {
        ObjectNode r = JSON.createObjectNode();
        ArrayNode list = r.putArray("tools");
        for (Tool t : TOOLS) {
            ObjectNode o = list.addObject().put("name", t.name()).put("description", t.description());
            ObjectNode schema = o.putObject("inputSchema").put("type", "object");
            ObjectNode props = schema.putObject("properties");
            for (String[] p : t.params()) {
                props.putObject(p[0]).put("type", p[1]).put("description", p[2]);
            }
            ArrayNode req = schema.putArray("required");
            for (String q : t.required()) {
                req.add(q);
            }
        }
        return r;
    }

    private ObjectNode call(JsonNode params) {
        String name = params.path("name").asText("");
        JsonNode a = params.path("arguments");
        Tool tool = TOOLS.stream().filter(t -> t.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown tool: " + name));
        for (String q : tool.required()) {
            if (a.path(q).asText("").isBlank()) {
                return text("'" + q + "' is required", true);
            }
        }
        KnowledgeBase k = kb();
        if (k.files().isEmpty()) {
            return text("No facts files found. Point logast mcp at them: --kb <folder>, LOGAST_KB, or kb.paths in "
                    + "~/.config/logast/config.yaml; `logast export` writes them.", true);
        }
        String answer = switch (name) {
            case "summary" -> k.summary();
            case "find" -> k.find(a.path("text").asText(), a.path("kind").asText(null), a.path("limit").asInt(30));
            case "describe" -> k.describe(a.path("id").asText(), a.path("limit").asInt(50));
            case "neighbors" -> k.neighbors(a.path("id").asText(), a.path("direction").asText(null), kinds(a.path("kinds")),
                    a.path("limit").asInt(50));
            case "path" -> k.path(a.path("from").asText(), a.path("to").asText(), a.path("maxDepth").asInt(8));
            case "runs_on" -> Schedules.runsOn(k, a.path("day").asText(), a.path("env").asText(null), a.path("zone").asText(null),
                    java.time.LocalDate.now(java.time.ZoneOffset.UTC));
            case "next_runs" -> Schedules.nextRuns(k, a.path("id").asText(), a.path("env").asText(null), a.path("count").asInt(5),
                    a.path("zone").asText(null), java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC));
            case "lineage" -> {
                boolean down = !"upstream".equalsIgnoreCase(a.path("direction").asText("downstream"));
                Knowledge.Flow flow = k.knowledge().flows().get(a.path("id").asText());
                if (flow == null) {
                    yield Lineage.lineage(k, a.path("id").asText(), down, a.path("depth").asInt(6), a.path("limit").asInt(200));
                }
                StringBuilder sb = new StringBuilder("flow \"" + flow.name() + "\"" + (flow.proposed() ? " (proposed)" : "") + ":\n");
                for (String n : flow.nodes()) {   // #168: a flow's name starts lineage from each of its nodes
                    sb.append(Lineage.lineage(k, n, down, a.path("depth").asInt(6), a.path("limit").asInt(200)));
                }
                yield sb.toString();
            }
            case "risks" -> Lineage.risks(k, a.path("env").asText(null));
            case "secrets" -> Secrets.list(k, a.path("text").asText(null), a.path("env").asText(null),
                    a.has("expiringDays") ? a.path("expiringDays").asInt() : null, java.time.LocalDate.now(java.time.ZoneOffset.UTC));
            case "workflow_refs" -> Workflows.refs(k, a.path("text").asText(null));
            case "flows" -> k.flows(a.path("text").asText(null));
            case "propose_flow", "propose_summary" -> {
                if (proposals == null) {
                    yield "This server is read-only: start logast mcp with a kb folder it may write knowledge.proposed.yml to.";
                }
                try {
                    yield name.equals("propose_flow")
                            ? Proposals.flow(proposals, k, a.path("name").asText(), a.path("description").asText(null), kinds(a.path("nodes")), kinds(a.path("sources")))
                            : Proposals.summary(proposals, k, a.path("id").asText(), a.path("text").asText(), kinds(a.path("sources")));
                } catch (IOException e) {
                    yield "Could not write " + proposals + ": " + e.getMessage();
                }
            }
            case "unknown_terms" -> k.unknownTerms(a.path("limit").asInt(40));
            case "term" -> k.knowledge().term(a.path("name").asText());
            case "read_name" -> {
                String r = k.knowledge().read(a.path("name").asText());
                yield r == null ? "\"" + a.path("name").asText() + "\": no abbreviation or alias in it is known" : r;
            }
            case "writers_of" -> k.tableAccess(a.path("table").asText(), true, a.path("limit").asInt(100));
            case "readers_of" -> k.tableAccess(a.path("table").asText(), false, a.path("limit").asInt(100));
            default -> throw new IllegalArgumentException("unknown tool: " + name);
        };
        return text(answer, false);
    }

    private static List<String> kinds(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n.isArray()) {
            n.forEach(x -> out.add(x.asText().strip()));
        } else if (!n.asText("").isBlank()) {
            for (String s : n.asText().split(",")) {
                out.add(s.strip());
            }
        }
        return out;
    }

    /** The facts, loaded again when the set of files or one of their sizes or times changed. */
    private KnowledgeBase kb() {
        List<Path> fs = files.get();
        StringBuilder stamp = new StringBuilder();
        for (Path f : fs) {
            try {
                stamp.append(f).append(Files.size(f)).append(Files.getLastModifiedTime(f).toMillis()).append('\n');
            } catch (IOException e) {
                stamp.append(f).append("?\n");
            }
        }
        if (kb == null || !stamp.toString().equals(loadedStamp)) {
            kb = KnowledgeBase.load(fs);
            loadedStamp = stamp.toString();
            log.println("logast mcp: " + kb.files().size() + " facts files, " + kb.nodeCount() + " nodes, " + kb.edgeCount() + " edges"
                    + (kb.problems().isEmpty() ? "" : ", " + kb.problems().size() + " lines left out"));
        }
        return kb;
    }

    private static ObjectNode text(String text, boolean isError) {
        ObjectNode r = JSON.createObjectNode();
        r.putArray("content").addObject().put("type", "text").put("text", text);
        r.put("isError", isError);
        return r;
    }

    private static ObjectNode result(JsonNode id, JsonNode result) {
        ObjectNode r = JSON.createObjectNode().put("jsonrpc", "2.0");
        r.set("id", id);
        r.set("result", result);
        return r;
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode r = JSON.createObjectNode().put("jsonrpc", "2.0");
        r.set("id", id == null ? JSON.nullNode() : id);
        r.putObject("error").put("code", code).put("message", message);
        return r;
    }
}
