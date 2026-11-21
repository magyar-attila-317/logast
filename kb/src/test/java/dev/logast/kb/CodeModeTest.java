package dev.logast.kb;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** #201: code mode: two tools (api, query) instead of twenty; programs run read-only in a sandbox. */
class CodeModeTest {
    @TempDir
    Path tmp;

    private KnowledgeBase kb() throws IOException {
        Files.writeString(tmp.resolve("shop.jsonl"), """
                {"header":{"schema":"kb-facts/0","extractor":"java","repo":"orders"}}
                {"node":{"id":"service:orders","kind":"service","attrs":{},"evidence":{"file":"build.gradle.kts"}}}
                {"node":{"id":"job:orders:com.example.ExportJob","kind":"job","attrs":{"entrypoint":"quartz","cron":"0 0 2 ? * MON-FRI","zone":"UTC"},"evidence":{"file":"ExportJob.java","line":9}}}
                {"node":{"id":"table:sales.order_export","kind":"table","attrs":{"columns":["id"]},"evidence":{"file":"V1__export.sql","line":1}}}
                {"edge":{"from":"job:orders:com.example.ExportJob","kind":"writes","to":"table:sales.order_export","provenance":"extracted","evidence":{"file":"ExportJob.java","line":20}}}
                """);
        Files.writeString(tmp.resolve("billing.jsonl"), """
                {"header":{"schema":"kb-facts/0","extractor":"java","repo":"billing"}}
                {"node":{"id":"job:billing:com.example.InvoiceJob","kind":"job","attrs":{"cron":"0 0 3 * * *"},"evidence":{"file":"InvoiceJob.java","line":4}}}
                {"edge":{"from":"job:billing:com.example.InvoiceJob","kind":"reads","to":"table:sales.order_export","provenance":"extracted","evidence":{"file":"InvoiceJob.java","line":12}}}
                """);
        return KnowledgeBase.load(List.of(tmp));
    }

    @Test
    void theApiSaysWhatIsLoadedAndHowToAsk() throws IOException {
        String api = CodeMode.api(kb(), null);
        assertThat(api).contains("declare function nodes(kind: string", "declare function runs(", "Loaded now: 2 repos; ",
                "2 job", "Edge kinds: reads, writes", "job: cron, entrypoint, zone", "// Examples");
    }

    @Test
    void oneProgramAnswersAQuestionOfSeveralSteps() throws IOException {
        CodeMode.Result r = CodeMode.run(kb(), """
                const svc = id => id.split(":")[1];
                return nodes("job").filter(j => runs(j.id, "2026-11-23").length).flatMap(j => out(j.id, "writes").map(w => ({
                  job: j.id, at: runs(j.id, "2026-11-23")[0], table: w.to, source: w.source,
                  readBy: into(w.to, "reads").map(e => e.from).filter(f => svc(f) !== svc(j.id)) })));
                """);
        assertThat(r.error()).isNull();
        JsonNode out = McpServer.JSON.readTree(r.json());
        assertThat(out).hasSize(1);
        assertThat(out.get(0).get("job").asText()).isEqualTo("job:orders:com.example.ExportJob");
        assertThat(out.get(0).get("at").asText()).isEqualTo("02:00");
        assertThat(out.get(0).get("readBy").get(0).asText()).isEqualTo("job:billing:com.example.InvoiceJob");
        assertThat(out.get(0).get("source").asText()).contains("ExportJob.java:20");
    }

    @Test
    void mistakesComeBackWithAHint() throws IOException {
        KnowledgeBase kb = kb();
        assertThat(CodeMode.run(kb, "return nodes('jobs').length").error()).contains("no kind 'jobs'; did you mean 'job'?", "kinds: ");
        assertThat(CodeMode.run(kb, "return nodes('job'").error()).startsWith("syntax error");
        assertThat(CodeMode.run(kb, "return 'x'.repeat(50000)").error()).contains("more than " + CodeMode.MAX_RESULT);
    }

    @Test
    void theSandboxStaysClosed() throws IOException {
        KnowledgeBase kb = kb();
        assertThat(CodeMode.run(kb, "return [typeof Java, typeof Packages, typeof java, typeof print, typeof load, typeof Polyglot].join()").json())
                .isEqualTo("\"undefined,undefined,undefined,undefined,undefined,undefined\"");
        assertThat(CodeMode.run(kb, "return Polyglot.eval('js', '1')").error()).isNotNull();
        long t0 = System.nanoTime();
        assertThat(CodeMode.run(kb, "while (true) {}").error()).startsWith("stopped: ");
        assertThat((System.nanoTime() - t0) / 1_000_000).as("stopped by its limits").isLessThan(CodeMode.TIMEOUT_MS + 3_000);
    }

    @Test
    void theFlagChoosesTheTools() throws IOException {
        KnowledgeBase kb = kb();
        List<Path> files = KnowledgeBase.factFiles(List.of(tmp));
        String list = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
        McpServer plain = new McpServer(() -> files, "1", new PrintStream(new ByteArrayOutputStream()), null, false);
        McpServer code = new McpServer(() -> files, "1", new PrintStream(new ByteArrayOutputStream()), null, true);
        assertThat(plain.handle(list).at("/result/tools").size()).isEqualTo(20);
        List<String> names = new ArrayList<>();
        code.handle(list).at("/result/tools").forEach(t -> names.add(t.get("name").asText()));
        assertThat(names).containsExactly("api", "query");
        JsonNode answer = code.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"query\","
                + "\"arguments\":{\"code\":\"return into('table:sales.order_export', 'reads').map(e => e.from)\"}}}");
        assertThat(answer.at("/result/isError").asBoolean()).isFalse();
        assertThat(answer.at("/result/content/0/text").asText()).isEqualTo("[\"job:billing:com.example.InvoiceJob\"]");
        assertThat(kb.nodeCount()).isPositive();
    }
}
