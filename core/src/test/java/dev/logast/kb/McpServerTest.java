package dev.logast.kb;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** #156: the knowledge base's questions, and the MCP server that asks them over stdio. */
class McpServerTest {
    @TempDir
    Path tmp;

    private static final String BILLING = """
            {"header":{"schema":"kb-facts/0","extractor":"java","repo":"billing","commit":"abc1234def"}}
            {"node":{"id":"endpoint:billing:POST /invoices","kind":"endpoint","attrs":{"method":"POST","path":"/invoices"},"evidence":{"file":"src/main/java/InvoiceController.java","line":20}}}
            {"node":{"id":"job:billing:LedgerSync.sync","kind":"job","attrs":{"cron":"0 0 5 * * *"},"evidence":{"file":"src/main/java/LedgerSync.java","line":12}}}
            {"node":{"id":"bean:billing:com.example.SyncJob","kind":"bean","attrs":{"profiles":["sync"]},"evidence":{"file":"src/main/java/SyncJob.java","line":8}}}
            {"edge":{"from":"endpoint:billing:POST /invoices","kind":"writes","to":"table:billing.invoice","attrs":{"via":"InvoiceRepository.save"},"provenance":"extracted","evidence":{"file":"src/main/java/InvoiceController.java","line":21}}}
            {"edge":{"from":"job:billing:LedgerSync.sync","kind":"reads","to":"table:billing.invoice","provenance":"extracted","evidence":{"file":"src/main/java/LedgerSync.java","line":14}}}
            {"edge":{"from":"job:billing:LedgerSync.sync","kind":"writes","to":"table:audit.event","provenance":"extracted","evidence":{"file":"src/main/java/LedgerSync.java","line":15}}}
            {"edge":{"from":"bean:billing:com.example.SyncJob","kind":"active-with","to":"profile:billing:sync","provenance":"extracted","evidence":{"file":"src/main/java/SyncJob.java","line":7}}}
            {"edge":{"from":"job:billing:LedgerSync.sync","kind":"runs","to":"bean:billing:com.example.SyncJob","provenance":"extracted","evidence":{"file":"src/main/java/SyncJob.java","line":8}}}
            """;
    private static final String AUDIT = """
            {"header":{"schema":"kb-facts/0","extractor":"java","repo":"audit"}}
            {"edge":{"from":"listener:audit:kafka audit-events","kind":"writes","to":"table:audit.event","provenance":"extracted","evidence":{"file":"src/main/java/AuditListener.java","line":30}}}
            {"node":{"id":"bad","kind":"service","evidence":{"file":"x"}}}
            """;

    private List<Path> files() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("kb/nested"));
        Files.writeString(tmp.resolve("kb/billing.jsonl"), BILLING);
        Files.writeString(dir.resolve("audit.jsonl"), AUDIT);
        Files.writeString(dir.resolve("notes.txt"), "not facts");
        return KnowledgeBase.factFiles(List.of(tmp.resolve("kb")));
    }

    @Test
    void questionsAreAnsweredWithTheirSources() throws IOException {
        KnowledgeBase kb = KnowledgeBase.load(files());
        assertThat(kb.files()).as("every *.jsonl below the folder").hasSize(2);
        assertThat(kb.problems()).singleElement().asString().contains("audit.jsonl:3: id 'bad' is not <kind>:<name>");

        assertThat(kb.tableAccess("event", true, 100)).as("a bare name matches any schema; writers from every repo, cited")
                .contains("table:audit.event: 2 places writes it",
                        "- job:billing:LedgerSync.sync  (billing/src/main/java/LedgerSync.java:15@abc1234)",
                        "- listener:audit:kafka audit-events  (audit/src/main/java/AuditListener.java:30)");
        assertThat(kb.tableAccess("billing.invoice", false, 100)).contains("1 place reads it", "job:billing:LedgerSync.sync");
        assertThat(kb.tableAccess("nothing", true, 100)).startsWith("No table nothing");

        assertThat(kb.find("ledger", null, 30)).contains("job:billing:LedgerSync.sync");
        assertThat(kb.find("0 0 5", "job", 30)).as("attributes are searched too").contains("job:billing:LedgerSync.sync");
        assertThat(kb.describe("job:billing:LedgerSync.sync", 50)).contains("cron=0 0 5 * * *", "-writes-> table:audit.event",
                "-runs-> bean:billing:com.example.SyncJob");
        assertThat(kb.neighbors("table:audit.event", "in", List.of("writes"), 50)).contains("listener:audit:kafka audit-events -writes->");

        assertThat(kb.path("job:billing:LedgerSync.sync", "profile:billing:sync", 8)).as("job → bean → profile")
                .contains("-runs-> bean:billing:com.example.SyncJob", "-active-with-> profile:billing:sync");
        assertThat(kb.path("profile:billing:sync", "job:billing:LedgerSync.sync", 8)).as("against the edges' direction")
                .startsWith("No forward chain");
        assertThat(kb.summary()).contains("2 facts files, 2 repos: audit, billing", "1 lines left out");
    }

    @Test
    void theServerSpeaksMcpOverStdio() throws IOException {
        List<Path> files = files();
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        McpServer server = new McpServer(() -> files, "1.2.3", new PrintStream(log, true));
        String in = String.join("\n",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{}}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"writers_of\",\"arguments\":{\"table\":\"audit.event\"}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"writers_of\",\"arguments\":{}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"nope\",\"arguments\":{}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"resources/list\"}",
                "not json",
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}") + "\n";
        StringWriter out = new StringWriter();
        server.run(new BufferedReader(new StringReader(in)), out);
        List<JsonNode> replies = new ArrayList<>();
        for (String line : out.toString().split("\n")) {
            replies.add(McpServer.JSON.readTree(line));
        }

        assertThat(replies).as("a reply per request, none for the notification").hasSize(8);
        JsonNode init = replies.get(0).get("result");
        assertThat(init.get("protocolVersion").asText()).as("the client's version").isEqualTo("2025-03-26");
        assertThat(init.at("/serverInfo/version").asText()).isEqualTo("1.2.3");
        assertThat(init.has("capabilities")).isTrue();
        List<String> tools = new ArrayList<>();
        replies.get(1).at("/result/tools").forEach(t -> tools.add(t.get("name").asText()));
        assertThat(tools).containsExactly("summary", "find", "describe", "neighbors", "path", "runs_on", "next_runs", "lineage", "risks", "secrets", "workflow_refs", "flows", "propose_flow", "propose_summary", "term", "read_name", "writers_of", "readers_of");
        assertThat(replies.get(1).at("/result/tools/16/inputSchema/required/0").asText()).isEqualTo("table");
        assertThat(replies.get(2).at("/result/isError").asBoolean()).isFalse();
        assertThat(replies.get(2).at("/result/content/0/text").asText()).contains("table:audit.event: 2 places writes it");
        assertThat(replies.get(3).at("/result/isError").asBoolean()).as("a missing argument is the tool's error").isTrue();
        assertThat(replies.get(4).at("/error/code").asInt()).as("an unknown tool").isEqualTo(-32602);
        assertThat(replies.get(5).at("/error/code").asInt()).as("an unknown method").isEqualTo(-32601);
        assertThat(replies.get(6).at("/error/code").asInt()).as("a line that is not JSON").isEqualTo(-32700);
        assertThat(replies.get(7).get("id").asInt()).isEqualTo(7);
        assertThat(log.toString()).as("diagnostics on the log, never on stdout").contains("2 facts files");
    }

    @Test
    void changedFactsAreReloaded() throws IOException {
        List<Path> files = files();
        McpServer server = new McpServer(() -> files, "1", new PrintStream(new ByteArrayOutputStream()));
        String ask = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"readers_of\",\"arguments\":{\"table\":\"audit.event\"}}}";
        assertThat(server.handle(ask).at("/result/content/0/text").asText()).contains("0 places reads it");
        Files.writeString(tmp.resolve("kb/billing.jsonl"), BILLING
                + "{\"edge\":{\"from\":\"job:billing:Report.run\",\"kind\":\"reads\",\"to\":\"table:audit.event\",\"provenance\":\"extracted\",\"evidence\":{\"file\":\"R.java\",\"line\":3}}}\n");
        assertThat(server.handle(ask).at("/result/content/0/text").asText()).contains("1 place reads it", "job:billing:Report.run");
    }

    @Test
    void noFactsSaysWhereToPutThem() {
        McpServer server = new McpServer(List::of, "1", new PrintStream(new ByteArrayOutputStream()));
        JsonNode r = server.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"summary\",\"arguments\":{}}}");
        assertThat(r.at("/result/isError").asBoolean()).isTrue();
        assertThat(r.at("/result/content/0/text").asText()).contains("--kb", "LOGAST_KB", "kb.paths", "logast export");
    }
}
