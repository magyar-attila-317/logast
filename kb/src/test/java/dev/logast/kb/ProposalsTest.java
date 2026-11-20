package dev.logast.kb;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** #168: the team's LLM proposes flows and summaries through MCP; people approve them in knowledge.yml. */
class ProposalsTest {
    @TempDir
    Path tmp;

    private static final String FACTS = """
            {"header":{"schema":"kb-facts/0","extractor":"java","repo":"shop"}}
            {"node":{"id":"job:billing:settle","kind":"job","attrs":{"schedule":"0 1 * * *"},"evidence":{"file":"V1__jobs.sql","line":3}}}
            {"node":{"id":"job:ledger:post","kind":"job","evidence":{"file":"V1__jobs.sql","line":4}}}
            {"edge":{"from":"job:billing:settle","kind":"writes","to":"table:billing.settlement","provenance":"extracted","evidence":{"file":"Settle.java","line":9}}}
            {"node":{"id":"script:org/ci/scripts/deploy.sh","kind":"script","evidence":{"file":"scripts/deploy.sh"}}}
            """;

    private JsonNode call(McpServer s, String tool, String args) {
        return s.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool + "\",\"arguments\":" + args + "}}");
    }

    private static String text(JsonNode r) {
        return r.at("/result/content/0/text").asText();
    }

    @Test
    void proposalsAreWrittenReadBackAndGoStale() throws IOException {
        Path kb = Files.createDirectories(tmp.resolve("kb"));
        Files.writeString(kb.resolve("shop.jsonl"), FACTS);
        Files.writeString(kb.resolve("knowledge.yml"), """
                # the people's file: untouched by proposals
                flows:
                  nightly posting:
                    description: Ledger posts what settlement wrote.
                    nodes: [job:ledger:post]
                """);
        Path proposals = Proposals.file(List.of(kb));
        assertThat(proposals).isEqualTo(kb.resolve("knowledge.proposed.yml"));
        McpServer s = new McpServer(() -> KnowledgeBase.factFiles(List.of(kb)), "1", new PrintStream(new ByteArrayOutputStream()), proposals);

        assertThat(text(call(s, "propose_flow", "{\"name\":\"month-end settlement\",\"description\":\"Settles the month and posts it\","
                + "\"nodes\":\"job:billing:settle, table:billing.settlement, job:ledger:post\",\"sources\":\"job:billing:settle, https://wiki.example.com/settlement\"}")))
                .startsWith("Proposed flow \"month-end settlement\" (3 nodes)");
        assertThat(text(call(s, "propose_flow", "{\"name\":\"x\",\"nodes\":\"job:nowhere:run\",\"sources\":\"a\"}")))
                .as("a node the facts do not have").startsWith("Not in the facts: job:nowhere:run");
        assertThat(text(call(s, "propose_flow", "{\"name\":\"x\",\"nodes\":\"job:ledger:post\",\"sources\":\"\"}")))
                .as("sources are required").contains("'sources' is required");
        assertThat(text(call(s, "propose_summary", "{\"id\":\"script:org/ci/scripts/deploy.sh\",\"text\":\"Deploys the chart to an environment\","
                + "\"sources\":\"scripts/deploy.sh\"}"))).startsWith("Proposed a summary");

        assertThat(Files.readString(kb.resolve("knowledge.yml"))).as("the people's file is not touched").startsWith("# the people's file");
        assertThat(Files.readString(proposals)).contains("month-end settlement", "by: llm", "evidence:", "shop/V1__jobs.sql:3");

        String flows = text(call(s, "flows", "{}"));
        assertThat(flows).contains("- nightly posting\n  Ledger posts what settlement wrote.",
                "- month-end settlement (proposed by llm, not approved yet)", "· table:billing.settlement");
        assertThat(flows).doesNotContain("STALE");
        assertThat(text(call(s, "describe", "{\"id\":\"script:org/ci/scripts/deploy.sh\"}")))
                .contains("summary (proposed by llm, not approved yet): Deploys the chart to an environment");
        assertThat(text(call(s, "describe", "{\"id\":\"job:billing:settle\"}"))).contains("part of flow \"month-end settlement\" (proposed)");
        assertThat(text(call(s, "lineage", "{\"id\":\"month-end settlement\"}"))).startsWith("flow \"month-end settlement\" (proposed):")
                .contains("writes → table:billing.settlement");

        Files.writeString(kb.resolve("shop.jsonl"), FACTS.replace("\"line\":3", "\"line\":7"));   // the job moved
        assertThat(text(call(s, "flows", "{\"text\":\"month\"}"))).contains("[STALE: the evidence of its nodes changed]");
    }

    @Test
    void withoutAPlaceToWriteTheServerIsReadOnly() throws IOException {
        Path kb = Files.createDirectories(tmp.resolve("kb"));
        Files.writeString(kb.resolve("shop.jsonl"), FACTS);
        McpServer s = new McpServer(() -> KnowledgeBase.factFiles(List.of(kb)), "1", new PrintStream(new ByteArrayOutputStream()));
        assertThat(text(call(s, "propose_flow", "{\"name\":\"x\",\"nodes\":\"job:ledger:post\",\"sources\":\"a\"}"))).contains("read-only");
        assertThat(Files.exists(kb.resolve("knowledge.proposed.yml"))).isFalse();
    }
}
