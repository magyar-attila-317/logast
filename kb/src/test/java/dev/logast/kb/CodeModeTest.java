package dev.logast.kb;

import dev.logast.facts.Cron;
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
                {"node":{"id":"table:kiosk.order_export","kind":"table","attrs":{"columns":["id"]},"evidence":{"file":"V1__export.sql","line":1}}}
                {"edge":{"from":"job:orders:com.example.ExportJob","kind":"writes","to":"table:kiosk.order_export","provenance":"extracted","evidence":{"file":"ExportJob.java","line":20}}}
                """);
        Files.writeString(tmp.resolve("billing.jsonl"), """
                {"header":{"schema":"kb-facts/0","extractor":"java","repo":"billing"}}
                {"node":{"id":"job:billing:com.example.InvoiceJob","kind":"job","attrs":{"cron":"0 0 3 * * *"},"evidence":{"file":"InvoiceJob.java","line":4}}}
                {"edge":{"from":"job:billing:com.example.InvoiceJob","kind":"reads","to":"table:kiosk.order_export","provenance":"extracted","evidence":{"file":"InvoiceJob.java","line":12}}}
                """);
        return KnowledgeBase.load(List.of(tmp));
    }

    @Test
    void theApiSaysWhatIsLoadedAndHowToAsk() throws IOException {
        String api = CodeMode.api(kb(), null);
        assertThat(api).contains("declare function nodes(kind: string", "declare function runs(", "Loaded now: 2 repos; ",
                "2 job", "Edge kinds: reads, writes", "job: cron, entrypoint, zone", "// Examples");
        assertThat(api).as("#215: which kinds run, which are raw values").contains("startup = one per profile",
                "Raw values to join on: property", "Filter and count in the program");
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
    void aLargeResultSaysWhatMakesItLarge() throws IOException {   // #229
        String err = CodeMode.run(kb(), """
                return Array.from({ length: 300 }, (_, i) => ({ id: "job:" + i, attrs: { text: "x".repeat(100) } }));
                """).error();
        assertThat(err).contains("more than " + CodeMode.MAX_RESULT, "a list of 300 items", "attrs ≈ 1", "return only the fields");
        assertThat(CodeMode.run(kb(), "return { jobs: Array(5000).fill('job'), n: 1 }").error()).contains("an object; jobs ≈", "(5000 items)");
    }

    @Test
    void anAnswerNamesTheSchedulesItDoesNotKnow() throws IOException {   // #228
        Files.writeString(tmp.resolve("ops.jsonl"), """
                {"header":{"schema":"kb-facts/0","extractor":"java","repo":"ops"}}
                {"node":{"id":"job:ops:com.example.CleanupJob","kind":"job","attrs":{"entrypoint":"quartz"},"evidence":{"file":"CleanupJob.java","line":3}}}
                """);
        CodeMode.Result r = CodeMode.run(kb(), "return nodes('job').filter(j => runs(j.id, '2026-11-23').length).map(j => j.id)");
        assertThat(r.json()).contains("ExportJob").doesNotContain("CleanupJob");
        assertThat(r.notes()).containsExactly("job:ops:com.example.CleanupJob: schedule not known (no schedule in the facts and nothing that runs it)");
        assertThat(r.notesText()).startsWith("Not known (say so in the answer");
        assertThat(CodeMode.run(kb(), "return runs('job:none', 'monday')").notes()).containsExactly("job:none: not in the facts");
        assertThat(CodeMode.run(kb(), "return nodes('job').length").notes()).as("nothing asked, nothing noted").isEmpty();
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
                + "\"arguments\":{\"code\":\"return into('table:kiosk.order_export', 'reads').map(e => e.from)\"}}}");
        assertThat(answer.at("/result/isError").asBoolean()).isFalse();
        assertThat(answer.at("/result/content/0/text").asText()).isEqualTo("[\"job:billing:com.example.InvoiceJob\"]");
        assertThat(kb.nodeCount()).isPositive();
    }

    /** #204: at fleet size (about 29k nodes, 3,000 jobs) the per-job lookups of a program stay well inside its limits. */
    @Test
    void aFleetSizedQueryStaysInsideItsLimits() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("fleet"));
        for (int r = 0; r < 150; r++) {
            StringBuilder sb = new StringBuilder("{\"header\":{\"schema\":\"kb-facts/0\",\"extractor\":\"java\",\"repo\":\"s" + r + "\"}}\n");
            for (int t = 0; t < 50; t++) {
                sb.append("{\"node\":{\"id\":\"table:s").append(r).append(".t").append(t).append("\",\"kind\":\"table\",\"attrs\":{},\"evidence\":{\"file\":\"V1.sql\"}}}\n");
            }
            for (int e = 0; e < 120; e++) {
                sb.append("{\"node\":{\"id\":\"endpoint:s").append(r).append(":GET /r").append(e).append("\",\"kind\":\"endpoint\",\"attrs\":{},\"evidence\":{\"file\":\"C.java\"}}}\n");
            }
            for (int j = 0; j < 20; j++) {
                String id = "job:s" + r + ":Job" + j;
                sb.append("{\"node\":{\"id\":\"").append(id).append("\",\"kind\":\"job\",\"attrs\":{\"cron\":\"0 ").append(j)
                        .append(" 2 * * *\"},\"evidence\":{\"file\":\"Job.java\"}}}\n");
                sb.append("{\"edge\":{\"from\":\"").append(id).append("\",\"kind\":\"writes\",\"to\":\"table:s").append(r).append(".t").append(j)
                        .append("\",\"provenance\":\"extracted\",\"evidence\":{\"file\":\"Job.java\"}}}\n");
            }
            Files.writeString(dir.resolve("s" + r + ".jsonl"), sb);
        }
        KnowledgeBase kb = KnowledgeBase.load(List.of(dir));
        assertThat(kb.nodeCount()).isGreaterThan(28_000);
        CodeMode.Result r = CodeMode.run(kb, """
                return nodes("job").filter(j => runs(j.id, "2026-11-23").length && node(j.id).length)
                  .flatMap(j => out(j.id, "writes").map(w => w.to)).length;
                """);
        assertThat(r.error()).isNull();
        assertThat(r.json()).isEqualTo("3000");
    }

    /** #218: schedule() says how a job is scheduled or that it is not known; runs() follows what runs a job. */
    @Test
    void anEmptyRunsIsNotNeverScheduleSays() throws IOException {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("sched");
        java.nio.file.Files.writeString(dir.resolve("f.jsonl"), """
                {"header":{"schema":"kb-facts/0","extractor":"java","repo":"calc"}}
                {"node":{"id":"job:calc:Cron","kind":"job","attrs":{"entrypoint":"scheduled","cron":"0 0 2 * * MON"},"evidence":{"file":"A.java"}}}
                {"node":{"id":"job:calc:Unknown","kind":"job","attrs":{"entrypoint":"quartz"},"evidence":{"file":"B.java"}}}
                {"node":{"id":"job:calc:Chained","kind":"job","attrs":{"entrypoint":"startup"},"evidence":{"file":"C.java"}}}
                {"node":{"id":"job:orch:Pipe","kind":"job","attrs":{"schedule":"0 0 1 * * WED","scheduleFormat":"spring"},"evidence":{"file":"V1.sql"}}}
                {"edge":{"from":"job:orch:Pipe","kind":"runs","to":"job:calc:Chained","provenance":"extracted","evidence":{"file":"V1.sql"}}}
                """);
        KnowledgeBase kb = KnowledgeBase.load(List.of(dir));
        CodeMode.Result r = CodeMode.run(kb, """
                return ["Cron", "Unknown", "Chained"].map(j => ({ j, schedule: schedule("job:calc:" + j), wed: runs("job:calc:" + j, "2026-11-25") }));
                """);
        assertThat(r.error()).isNull();
        assertThat(r.json()).contains("cron 0 0 2 * * MON", "not known: no schedule in the facts", "(run by job:orch:Pipe)", "01:00");
        assertThat(CodeMode.api(kb, null)).contains("declare function schedule(", "An empty runs() is not");
    }
}
