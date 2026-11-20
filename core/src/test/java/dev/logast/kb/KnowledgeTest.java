package dev.logast.kb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** #163: knowledge.yml applied to the facts. */
class KnowledgeTest {
    @TempDir
    Path tmp;

    private KnowledgeBase kb(String knowledge) throws IOException {
        Files.writeString(tmp.resolve("facts.jsonl"), """
                {"header":{"schema":"kb-facts/0","extractor":"java","repo":"shop"}}
                {"node":{"id":"service:orders","kind":"service","evidence":{"file":"orders/build.gradle.kts"}}}
                {"node":{"id":"service:billing","kind":"service","evidence":{"file":"billing/build.gradle.kts"}}}
                {"node":{"id":"service:shipping","kind":"service","evidence":{"file":"shipping/build.gradle.kts"}}}
                {"node":{"id":"deployment:order-service","kind":"deployment","env":"prod","evidence":{"file":"charts/orders/templates/deployment.yaml"}}}
                {"node":{"id":"deployment:billing","kind":"deployment","env":"prod","evidence":{"file":"charts/billing/templates/deployment.yaml"}}}
                {"node":{"id":"deployment:shipping","kind":"deployment","env":"prod","evidence":{"file":"charts/shipping/templates/deployment-v2.yaml"}}}
                {"edge":{"from":"job:orders:Sync.run","kind":"writes","to":"table:billing.ord_inv_ref","provenance":"extracted","evidence":{"file":"Sync.java","line":3}}}
                """);
        Files.createDirectories(tmp.resolve("conf"));
        Files.writeString(tmp.resolve("conf/knowledge.yml"), knowledge);
        return KnowledgeBase.load(List.of(tmp));
    }

    private static final String KNOWLEDGE = """
            glossary:
              order:
                definition: A customer's request to buy, from checkout to delivery.
                source: https://wiki.example.com/glossary#order
                aliases: [purchase]
              invoice: The bill sent for an order.
            abbreviations:
              ord: order
              inv: invoice
              ref: reference
            services:
              orders: [order-service]
            decisions:
              - link: "service:billing -deployed-as-> deployment:billing"
                env: prod
                decision: reject
                by: jane
              - link: "service:shipping -deployed-as-> deployment:shipping"
                decision: approve
                by: sam
                evidence: shop/charts/shipping/templates/deployment.yaml
            """;

    @Test
    void decisionsAliasesAndNamesApply() throws IOException {
        KnowledgeBase kb = kb(KNOWLEDGE);
        assertThat(kb.problems()).isEmpty();
        assertThat(kb.neighbors("service:orders", "out", List.of("deployed-as"), 10)).as("another name of the service")
                .contains("deployment:order-service [prod] [inferred via identity:alias]");
        assertThat(kb.neighbors("service:billing", "out", List.of("deployed-as"), 10)).as("rejected").doesNotContain("deployed-as");
        assertThat(kb.neighbors("service:shipping", "out", List.of("deployed-as"), 10)).as("approved on other evidence: stale, decided again")
                .contains("decision=stale: approved on shop/charts/shipping/templates/deployment.yaml");
        assertThat(kb.summary()).contains("knowledge: 1 file(s)", "decisions to take again");
        assertThat(kb.describe("table:billing.ord_inv_ref", 10)).contains("reads as: order invoice reference");
        assertThat(kb.knowledge().read("ordInvRef")).isEqualTo("order invoice reference");
        assertThat(kb.knowledge().read("customer_id")).as("nothing known: no reading").isNull();
    }

    @Test
    void anApprovalOnTheSameEvidenceIsMarked() throws IOException {
        KnowledgeBase kb = kb(KNOWLEDGE.replace("deployment.yaml\n", "deployment-v2.yaml\n"));
        assertThat(kb.neighbors("service:shipping", "out", List.of("deployed-as"), 10)).contains("decision=approved by sam");
    }

    @Test
    void termsAliasesAndAbbreviations() throws IOException {
        Knowledge k = kb(KNOWLEDGE).knowledge();
        assertThat(k.term("Order")).startsWith("order\nA customer's request").contains("source: https://wiki.example.com/glossary#order");
        assertThat(k.term("purchase")).contains("(purchase: an alias of order)");
        assertThat(k.term("inv")).contains("(inv: abbreviation of invoice)", "The bill sent for an order.");
        assertThat(k.term("ref")).isEqualTo("ref: abbreviation of \"reference\" (not in the glossary)");
        assertThat(k.term("shipment")).contains("is not in the glossary");
        assertThat(Knowledge.tokens("ord_inv-ref.HTTPClient/orderId")).containsExactly("ord", "inv", "ref", "HTTP", "Client", "order", "Id");
    }

    @Test
    void aBrokenDecisionIsReported() throws IOException {
        KnowledgeBase kb = kb("decisions:\n  - link: x\n    decision: maybe\n");
        assertThat(kb.problems()).singleElement().asString().contains("decision: approve or reject");
    }
}
