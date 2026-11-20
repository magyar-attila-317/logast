package dev.logast.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.logast.fleet.FleetHome;
import dev.logast.kb.KbPaths;
import dev.logast.kb.KnowledgeBase;
import dev.logast.kb.McpServer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;

/** #156: an MCP server over the facts of many repos (docs/mcp.md). */
@Command(name = "mcp", hidden = true, description = "MCP server (stdio) that answers questions over facts files of many "
        + "repos: who writes a table, what an endpoint calls, which entrypoints run a profile (docs/mcp.md)",
        subcommands = {McpCmd.Config.class, McpCmd.ReviewPage.class})
final class McpCmd implements Callable<Integer> {
    @Option(names = "--kb", description = "A facts file or a folder of them (repeatable; default: LOGAST_KB, else kb.paths "
            + "in ~/.config/logast/config.yaml)")
    List<Path> kb = new ArrayList<>();

    @Override
    public Integer call() throws IOException {
        KbPaths.Resolved r = KbPaths.resolve(kb, FleetHome.Env.current());
        System.err.println("logast mcp: facts from " + r.source() + ": " + r.paths());
        McpServer server = new McpServer(() -> KnowledgeBase.factFiles(r.paths()), version(), System.err,
                r.paths().isEmpty() ? null : dev.logast.kb.Proposals.file(r.paths()));
        Writer out = new OutputStreamWriter(System.out, StandardCharsets.UTF_8);
        server.run(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), out);
        return 0;
    }

    @Command(name = "config", description = "Print the server entry to add to an MCP client's configuration (the common "
            + "mcpServers JSON shape)")
    static final class Config implements Callable<Integer> {
        @Option(names = "--kb", description = "Facts files or folders the entry names (default: none, then the server "
                + "reads LOGAST_KB or kb.paths)")
        List<Path> kb = new ArrayList<>();
        @Option(names = "--name", defaultValue = "logast-kb", description = "The server's name in the client (default: ${DEFAULT-VALUE})")
        String name;

        @Override
        public Integer call() throws IOException {
            ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            ObjectNode root = json.createObjectNode();
            ObjectNode server = root.putObject("mcpServers").putObject(name);
            server.put("command", "logast");
            ArrayNode args = server.putArray("args").add("mcp");
            for (Path p : kb) {
                args.add("--kb").add(p.toAbsolutePath().normalize().toString());
            }
            System.out.println(json.writeValueAsString(root));
            return 0;
        }
    }

    @Command(name = "review", description = "A local page to approve or reject inferred links and the LLM's proposals; "
            + "decisions go to knowledge.decisions.yml (docs/mcp.md)")
    static final class ReviewPage implements Callable<Integer> {
        @Option(names = "--kb", description = "Facts files or folders (default: LOGAST_KB, else kb.paths)")
        List<Path> kb = new ArrayList<>();
        @Option(names = "--by", description = "Who decides (default: the user name)")
        String by;
        @Option(names = "--port", defaultValue = "0", description = "Port (0 = any free port)")
        int port;
        @Option(names = "--open", description = "Open the page in the default browser")
        boolean open;

        @Override
        public Integer call() throws Exception {
            KbPaths.Resolved r = KbPaths.resolve(kb, FleetHome.Env.current());
            if (r.paths().isEmpty()) {
                Out.warn("no facts: give --kb <folder>, or set LOGAST_KB or kb.paths");
                return 2;
            }
            Path proposed = dev.logast.kb.Proposals.file(r.paths());
            Path decisions = proposed.resolveSibling(dev.logast.kb.KbReview.FILE);
            dev.logast.kb.KbReview page = new dev.logast.kb.KbReview(() -> KnowledgeBase.load(KnowledgeBase.factFiles(r.paths())), decisions,
                    proposed, by != null ? by : System.getProperty("user.name"));
            String url = page.start(port);
            System.out.println("review: " + url + "  (decisions go to " + decisions + "; Ctrl+C ends)");
            if (open) {
                String os = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
                try {
                    new ProcessBuilder(os.contains("mac") ? new String[]{"open", url} : new String[]{"xdg-open", url}).start();
                } catch (IOException e) {
                    System.out.println("could not open a browser; open the URL above");
                }
            }
            Thread.currentThread().join();
            return 0;
        }
    }

    static String version() throws IOException {
        Properties p = new Properties();
        try (var in = McpCmd.class.getResourceAsStream("/logast-version.properties")) {
            if (in != null) {
                p.load(in);
            }
        }
        return p.getProperty("version", "dev");
    }
}
