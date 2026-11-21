package dev.logast.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
        subcommands = {McpCmd.Config.class, McpCmd.ReviewPage.class, McpCmd.Vocabulary.class})
final class McpCmd implements Callable<Integer> {
    @Option(names = "--kb", description = "A facts file or a folder of them (repeatable; default: LOGAST_KB, else kb.paths "
            + "in ~/.config/logast/config.yaml)")
    List<Path> kb = new ArrayList<>();
    @Option(names = "--code-mode", description = "Offer two tools instead of twenty (#201): api (the facts API, what is "
            + "loaded, examples) and query (a read-only JavaScript program over the facts, run in a sandbox)")
    boolean codeMode;
    @Option(names = "--log", description = "Also append to this file the server's stderr (startup, timings, errors) and "
            + "every message in (> line) and out (< line) (#210): MCP clients start the server without a shell, so a 2>> "
            + "in their config does not work")
    Path log;

    @Override
    public Integer call() throws IOException {
        java.io.OutputStream file = log == null ? null : open(log);
        java.io.PrintStream err = file == null ? System.err : new java.io.PrintStream(tee(System.err, file), true, StandardCharsets.UTF_8);
        KbPaths.Resolved r = KbPaths.resolve(kb, KbPaths.Env.current());
        err.println("logast mcp " + build() + ": facts from " + r.source() + ": " + r.paths() + (codeMode ? " (code mode)" : ""));
        for (Path p : r.paths()) {
            if (!java.nio.file.Files.exists(p)) {
                err.println("logast mcp: " + p + " does not exist: no facts from it (#210)");
            }
        }
        McpServer server = new McpServer(() -> KnowledgeBase.factFiles(r.paths()), build(), err,
                r.paths().isEmpty() ? null : dev.logast.kb.Proposals.file(r.paths()), codeMode);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        Writer out = new OutputStreamWriter(System.out, StandardCharsets.UTF_8);
        if (file != null) {
            in = traffic(in, file);
            out = new OutputStreamWriter(tee(System.out, new Prefixed(file, "< ")), StandardCharsets.UTF_8);
        }
        server.run(in, out);
        err.flush();
        if (file != null) {
            file.close();
        }
        return 0;
    }

    private static java.io.OutputStream open(Path file) throws IOException {
        Path abs = file.toAbsolutePath();
        if (abs.getParent() != null) {
            java.nio.file.Files.createDirectories(abs.getParent());
        }
        java.io.OutputStream f = java.nio.file.Files.newOutputStream(abs, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        return new java.io.OutputStream() {   // stderr and traffic share it: each write whole
            @Override
            public synchronized void write(int b) throws IOException {
                f.write(b);
            }

            @Override
            public synchronized void write(byte[] b, int off, int len) throws IOException {
                f.write(b, off, len);
            }

            @Override
            public synchronized void flush() throws IOException {
                f.flush();
            }

            @Override
            public void close() throws IOException {
                f.close();
            }
        };
    }

    /** #210: what goes to {@code a} also goes to {@code b}. */
    static java.io.OutputStream tee(java.io.OutputStream a, java.io.OutputStream b) {
        return new java.io.OutputStream() {
            @Override
            public void write(int x) throws IOException {
                a.write(x);
                b.write(x);
            }

            @Override
            public void write(byte[] x, int off, int len) throws IOException {
                a.write(x, off, len);
                b.write(x, off, len);
            }

            @Override
            public void flush() throws IOException {
                a.flush();
                b.flush();
            }
        };
    }

    /** Lines written to the log with a prefix (a reply is one line, written whole before its flush). */
    private static final class Prefixed extends java.io.OutputStream {
        private final java.io.OutputStream to;
        private final byte[] prefix;
        private final java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();

        Prefixed(java.io.OutputStream to, String prefix) {
            this.to = to;
            this.prefix = prefix.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void write(int b) throws IOException {
            line.write(b);
            if (b == '\n') {
                emit();
            }
        }

        @Override
        public void flush() throws IOException {
            if (line.size() > 0) {
                emit();
            }
            to.flush();
        }

        private void emit() throws IOException {
            byte[] all = new byte[prefix.length + line.size()];
            System.arraycopy(prefix, 0, all, 0, prefix.length);
            System.arraycopy(line.toByteArray(), 0, all, prefix.length, line.size());
            to.write(all, 0, all.length);
            line.reset();
        }
    }

    /** The messages read, each also logged as a {@code > } line. */
    private static BufferedReader traffic(BufferedReader in, java.io.OutputStream file) {
        return new BufferedReader(in) {
            @Override
            public String readLine() throws IOException {
                String l = in.readLine();
                if (l != null && !l.isBlank()) {
                    file.write(("> " + l + "\n").getBytes(StandardCharsets.UTF_8));
                    file.flush();
                }
                return l;
            }
        };
    }

    @Command(name = "config", description = "Print the server entry to add to an MCP client's configuration (the common "
            + "mcpServers JSON shape)")
    static final class Config implements Callable<Integer> {
        @Option(names = "--kb", description = "Facts files or folders the entry names (default: none, then the server "
                + "reads LOGAST_KB or kb.paths)")
        List<Path> kb = new ArrayList<>();
        @Option(names = "--name", defaultValue = "logast-kb", description = "The server's name in the client (default: ${DEFAULT-VALUE})")
        String name;
        @Option(names = "--code-mode", description = "The entry starts the server in code mode (two tools: api, query)")
        boolean codeMode;
        @Option(names = "--log", arity = "0..1", fallbackValue = "", paramLabel = "<file>", description = "The entry has the "
                + "server append its stderr to a file (#210; without a file: logast-mcp.log, or logast-mcp-code.log in code "
                + "mode, in the temp folder)")
        String log;

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
            if (codeMode) {
                args.add("--code-mode");
            }
            if (log != null) {
                Path f = log.isEmpty() ? Path.of(System.getProperty("java.io.tmpdir"), codeMode ? "logast-mcp-code.log" : "logast-mcp.log")
                        : Path.of(log);
                args.add("--log").add(f.toAbsolutePath().normalize().toString());
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
            KbPaths.Resolved r = KbPaths.resolve(kb, KbPaths.Env.current());
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

    @Command(name = "vocabulary", description = "Print the knowledge.yml glossary as a vocabulary: block for logast's "
            + "company.yaml (with --company, only what that file does not have yet)")
    static final class Vocabulary implements Callable<Integer> {
        @Option(names = "--kb", description = "Facts files or folders (default: LOGAST_KB, else kb.paths)")
        List<Path> kb = new ArrayList<>();
        @Option(names = "--company", description = "logast's company.yaml: leave out the terms and aliases it has")
        Path company;

        @Override
        public Integer call() throws IOException {
            KbPaths.Resolved r = KbPaths.resolve(kb, KbPaths.Env.current());
            KnowledgeBase k = KnowledgeBase.load(KnowledgeBase.factFiles(r.paths()));
            java.util.Map<String, java.util.Set<String>> existing = new java.util.LinkedHashMap<>();
            if (company != null) {
                var root = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()).readTree(company.toFile());
                if (root != null) {
                    root.path("vocabulary").fields().forEachRemaining(e -> {
                        java.util.Set<String> aliases = new java.util.LinkedHashSet<>();
                        e.getValue().path("aliases").forEach(a -> aliases.add(a.asText()));
                        existing.put(e.getKey(), aliases);
                    });
                }
            }
            System.out.print(k.knowledge().vocabularyYaml(existing));
            return 0;
        }
    }

    /** #187: the exact build, version and commit (every build between releases has the same version). */
    static String build() throws IOException {
        Properties p = new Properties();
        try (var in = McpCmd.class.getResourceAsStream("/logast-version.properties")) {
            if (in != null) {
                p.load(in);
            }
        }
        return p.getProperty("version", "dev") + " (" + p.getProperty("commit", "unknown") + ")";
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
