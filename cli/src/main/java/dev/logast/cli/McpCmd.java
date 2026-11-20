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
        subcommands = McpCmd.Config.class)
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
