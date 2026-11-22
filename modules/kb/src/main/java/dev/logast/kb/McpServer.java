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
import java.util.Objects;
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
        this(files, version, log, proposals, false);
    }

    /** #201: {@code codeMode}: the two code-mode tools (api, query) instead of the twenty. */
    private final boolean codeMode;

    public McpServer(Supplier<List<Path>> files, String version, PrintStream log, Path proposals, boolean codeMode) {
        this.codeMode = codeMode;
        this.files = files;
        this.version = version;
        this.log = log;
        this.proposals = proposals;
        this.installed = installStamp();
    }

    /** #195: the installation this server runs from, when it started (the jar holding this class: its time and size). */
    private final String installed;
    static volatile java.util.function.Supplier<String> installProbe = McpServer::jarStamp;

    private static String installStamp() {
        try {
            return installProbe.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String jarStamp() {
        try {
            var src = McpServer.class.getProtectionDomain().getCodeSource();
            Path p = src == null ? null : Path.of(src.getLocation().toURI());
            return p == null || !Files.exists(p) ? null : Files.getLastModifiedTime(p).toMillis() + ":" + Files.size(p);
        } catch (Exception e) {
            return "gone";
        }
    }

    /** #195: logast was installed again since this server started: it still runs the old code until the client restarts it. */
    String staleNote() {
        String now = installStamp();
        if (installed == null || Objects.equals(installed, now)) {
            return null;
        }
        return "Note: logast was updated since this MCP server started (it runs logast " + version + " still). Restart the MCP "
                + "client (or reconnect the server) to answer with the new version.";
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
            // #202: a request carrying the 2026-07-28 per-request _meta is served statelessly by that revision; one
            // without it (after initialize) the legacy way. Both in one process (a dual-era server).
            JsonNode meta = msg.path("params").path("_meta");
            String version = meta.path(META + "protocolVersion").asText("");
            boolean modern = !version.isEmpty() || method.equals("server/discover");
            if (!version.isEmpty() && !MODERN.contains(version)) {
                ObjectNode e = error(id, -32022, "Unsupported protocol version");
                ObjectNode data = ((ObjectNode) e.get("error")).putObject("data");
                MODERN.forEach(data.putArray("supported")::add);
                data.put("requested", version);
                return e;
            }
            if (!version.isEmpty() && !meta.has(META + "clientCapabilities")) {
                return error(id, -32602, "_meta is missing " + META + "clientCapabilities");
            }
            ObjectNode out = switch (method) {
                case "initialize" -> initialize(msg.path("params"));
                case "server/discover" -> discover();
                case "ping" -> JSON.createObjectNode();
                case "tools/list" -> {
                    ObjectNode t = codeMode ? codeTools() : tools();
                    if (modern) {   // 2026-07-28: how long the list holds, for whom
                        t.put("ttlMs", 300_000).put("cacheScope", "private");
                    }
                    yield t;
                }
                case "tools/call" -> call(msg.path("params"));
                default -> null;
            };
            if (out == null) {
                return error(id, -32601, "method not found: " + method);
            }
            if (modern) {
                out.put("resultType", "complete");
                out.putObject("_meta").putObject(META + "serverInfo").put("name", "logast-kb").put("version", this.version);
            }
            return result(id, out);
        } catch (IllegalArgumentException e) {
            return error(id, -32602, e.getMessage());
        } catch (RuntimeException e) {
            log.println("logast mcp: " + e);
            return error(id, -32603, "internal error: " + e.getMessage());
        }
    }

    static final String META = "io.modelcontextprotocol/";
    /** #202: the stateless revisions this server speaks (per request); older clients use initialize. */
    static final List<String> MODERN = List.of("2026-07-28");

    /** #202: 2026-07-28's replacement of initialize's discovery: versions, capabilities, instructions. */
    private ObjectNode discover() {
        ObjectNode r = JSON.createObjectNode();
        MODERN.forEach(r.putArray("supportedVersions")::add);
        r.putObject("capabilities").putObject("tools");
        r.put("instructions", instructions());
        r.put("ttlMs", 300_000).put("cacheScope", "private");   // the facts are the team's, not public
        return r;
    }

    private String instructions() {
        return "Facts about code and deployment of many repos (docs/facts.md in logast). Node ids are "
                + "<kind>:<name>, e.g. service:shipping, endpoint:shipping:GET /shipments/{id}, table:billing.invoice, "
                + "job:shipping:ExportJob.run. Start with summary or find; cite the (repo/file:line) of each fact you use."
                + loaded();
    }

    private ObjectNode initialize(JsonNode params) {
        ObjectNode r = JSON.createObjectNode();
        String asked = params.path("protocolVersion").asText("");
        r.put("protocolVersion", asked.isBlank() ? PROTOCOL : asked);
        r.putObject("capabilities").putObject("tools");
        r.putObject("serverInfo").put("name", "logast-kb").put("version", version);
        r.put("instructions", instructions());
        return r;
    }

    /** What to look for in them per kind of question (#200): which kinds answer it, which tool asks. */
    private static final String[][] GUIDE = {
            {"release", "what is deployed where (helmfile releases per environment: chart, version, namespace, values): "
                    + "find kind=release, describe"},
            {"deployment", "how a service runs per environment (from the rendered charts: image, replicas, env vars, mounted "
                    + "secrets): find kind=deployment, describe; a service's workloads: neighbors (deployed-as)"},
            {"cronjob", "Kubernetes CronJobs per environment: runs_on, next_runs"},
            {"secret", "Key Vault secrets and who mounts or reads them: secrets"},
            {"resource", "cloud resources (Terraform/Terragrunt per environment): find kind=resource; secrets"},
            {"job", "scheduled work (@Scheduled, Quartz with triggers and time zones, Kubernetes cron): runs_on, next_runs"},
            {"table", "who reads and writes data: writers_of, readers_of; what follows from a write: lineage"},
            {"endpoint", "HTTP entrypoints and who calls them: find kind=endpoint, neighbors, lineage"},
            {"topic", "messages: find kind=topic, neighbors (publishes, consumes)"},
            {"workflow", "CI (GitHub Actions) and the runners: workflow_refs, runners"},
    };

    /** #200: what is loaded, so a client knows what it can ask before guessing (an empty string when nothing is). */
    private String loaded() {
        KnowledgeBase k;
        try {
            k = kb();
        } catch (RuntimeException e) {
            return "";
        }
        if (k.files().isEmpty()) {
            return "";
        }
        java.util.Map<String, Integer> kinds = k.kindCounts();
        StringBuilder sb = new StringBuilder("\n\nLoaded now: " + k.repoCount() + " repos; ");
        sb.append(String.join(", ", kinds.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue())
                .map(e -> e.getValue() + " " + e.getKey()).toList()));
        if (!k.environments().isEmpty()) {
            sb.append("; environments: ").append(String.join(", ", k.environments()));
        }
        sb.append(".\nWhere to look:");
        for (String[] g : GUIDE) {
            if (kinds.containsKey(g[0])) {
                sb.append("\n- ").append(g[0]).append(" (").append(kinds.get(g[0])).append("): ").append(g[1]);
            }
        }
        return sb.toString();
    }

    // ---- tools --------------------------------------------------------------------------------------------------------

    private record Tool(String name, String description, String[][] params, String[] required) {
    }

    /**
     * #186: no prefix of our own (clients already prefix MCP tools with the server's name: mcp_logast-kb_find);
     * calls by the briefly used {@code kb_<name>} still work.
     */
    static final String PREFIX = "kb_";
    /** #186: a parameter for tools that need none: some model APIs refuse an object schema without properties. */
    private static final String[] FILTER = {"text", "string", "only lines containing this text (case-insensitive)"};

    private static final List<Tool> TOOLS = List.of(
            new Tool("summary", "What is loaded: facts files, repos, and nodes and edges by kind.", new String[][]{FILTER}, new String[0]),
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
            new Tool("runners", "Self-hosted runner hosts (from Ansible): groups, labels, count, the workflows that run on them, and "
                    + "self-hosted workflows no runner fits.", new String[][]{FILTER}, new String[0]),
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

    /** The lines of {@code answer} that contain {@code text}; all of them when it is blank. */
    static String filter(String answer, String text) {
        if (text == null || text.isBlank()) {
            return answer;
        }
        String t = text.toLowerCase(java.util.Locale.ROOT);
        return answer.lines().filter(l -> l.toLowerCase(java.util.Locale.ROOT).contains(t))
                .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
    }

    /** tools/list's answer for a set of tools: name, description and input schema of each. */
    private static ObjectNode toolList(List<Tool> tools) {
        ObjectNode r = JSON.createObjectNode();
        ArrayNode list = r.putArray("tools");
        for (Tool t : tools) {
            ObjectNode o = list.addObject().put("name", t.name()).put("description", t.description());
            ObjectNode schema = o.putObject("inputSchema").put("type", "object");
            ObjectNode props = schema.putObject("properties");
            for (String[] p : t.params()) {
                props.putObject(p[0]).put("type", p[1]).put("description", p[2]);
            }
            if (t.required().length > 0) {   // #186: no empty list (strict schema checkers refuse it)
                ArrayNode req = schema.putArray("required");
                for (String q : t.required()) {
                    req.add(q);
                }
            }
        }
        return r;
    }

    private static ObjectNode tools() {
        return toolList(TOOLS);
    }

    /** #201: the code-mode tools. */
    private static final List<Tool> CODE_TOOLS = List.of(
            new Tool("api", "Read this first: the facts API (a TypeScript declaration) for the query tool, what is loaded now "
                    + "(kinds, counts, environments, attributes per kind) and worked examples.",
                    new String[][]{{"kind", "string", "only the attributes of this kind"}}, new String[0]),
            new Tool("query", "Run a read-only JavaScript program over the facts (the api tool describes the functions: nodes, "
                    + "node, out, into, find, runs, lineage); its `return` value comes back as JSON. One program can answer what "
                    + "takes many lookups: filter, follow edges, join and count in it, return only the answer with sources.",
                    new String[][]{{"code", "string", "the program; end with `return <value>`"}}, new String[]{"code"}));

    private static ObjectNode codeTools() {
        return toolList(CODE_TOOLS);
    }

    private ObjectNode codeCall(String name, JsonNode a) {
        KnowledgeBase k = kb();
        return switch (name) {
            case "api" -> text(CodeMode.api(k, a.path("kind").asText(null)), false);
            case "query" -> {
                String code = a.path("code").asText("");
                if (code.isBlank()) {
                    yield text("'code' is required: a JavaScript program ending with `return <value>` (see the api tool)", true);
                }
                long t0 = System.nanoTime();
                CodeMode.Result r = CodeMode.run(k, code);
                // #204: what code mode costs, for comparing sessions: time, result size, the first line of an error
                log.printf("logast mcp: query %d ms, %s%n", (System.nanoTime() - t0) / 1_000_000, r.error() != null
                        ? "error: " + r.error().lines().findFirst().orElse("") : r.json().length() + " characters");
                yield r.error() != null ? text(r.error() + "\n(the api tool lists the functions, kinds and attributes)", true)
                        : text(r.notes().isEmpty() ? r.json() : r.json() + "\n\n" + r.notesText(), false);   // #228
            }
            default -> throw new IllegalArgumentException("unknown tool: " + name + " (code mode: api, query)");
        };
    }

    private ObjectNode call(JsonNode params) {
        if (codeMode) {
            return codeCall(params.path("name").asText(""), params.path("arguments"));
        }
        String called = params.path("name").asText("");
        String name = called.startsWith(PREFIX) ? called.substring(PREFIX.length()) : called;
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
            case "summary" -> filter(k.summary(), a.path("text").asText(null));
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
            case "runners" -> filter(Lineage.runners(k), a.path("text").asText(null));
            case "term" -> k.knowledge().term(a.path("name").asText());
            case "read_name" -> {
                String r = k.knowledge().read(a.path("name").asText());
                yield r == null ? "\"" + a.path("name").asText() + "\": no abbreviation or alias in it is known" : r;
            }
            case "writers_of" -> k.tableAccess(a.path("table").asText(), true, a.path("limit").asInt(100));
            case "readers_of" -> k.tableAccess(a.path("table").asText(), false, a.path("limit").asInt(100));
            default -> throw new IllegalArgumentException("unknown tool: " + name);
        };
        String stale = staleNote();
        return text(stale == null ? answer : answer + "\n\n" + stale, false);
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
