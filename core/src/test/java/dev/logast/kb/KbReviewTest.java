package dev.logast.kb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** #169: the review page: what waits for a person, and decisions written to knowledge.decisions.yml. */
class KbReviewTest {
    @TempDir
    Path tmp;

    private Path kb() throws IOException {
        Path kb = Files.createDirectories(tmp.resolve("kb"));
        Files.writeString(kb.resolve("facts.jsonl"), """
                {"header":{"schema":"kb-facts/0","extractor":"java","repo":"shop"}}
                {"node":{"id":"service:orders","kind":"service","evidence":{"file":"orders/build.gradle.kts"}}}
                {"node":{"id":"service:billing","kind":"service","evidence":{"file":"billing/build.gradle.kts"}}}
                {"node":{"id":"deployment:orders","kind":"deployment","env":"prod","evidence":{"file":"charts/orders/deployment.yaml"}}}
                {"node":{"id":"deployment:billing","kind":"deployment","env":"prod","evidence":{"file":"charts/billing/deployment.yaml"}}}
                {"node":{"id":"job:billing:settle","kind":"job","evidence":{"file":"Settle.java","line":3}}}
                """);
        Files.writeString(kb.resolve("knowledge.yml"), "# people's notes\nabbreviations:\n  ord: order\n");
        Files.writeString(kb.resolve(Proposals.FILE), """
                flows:
                  settlement:
                    description: Settles the month
                    nodes: [job:billing:settle]
                    sources: [job:billing:settle]
                    by: llm
                    evidence:
                      job:billing:settle: shop/Settle.java:3
                """);
        return kb;
    }

    @Test
    void decisionsGoToTheirOwnFileAndApply() throws IOException {
        Path kb = kb();
        KbReview review = new KbReview(() -> KnowledgeBase.load(KnowledgeBase.factFiles(List.of(kb))), kb.resolve(KbReview.FILE),
                kb.resolve(Proposals.FILE), "jane");
        List<KbReview.Item> items = KbReview.items(KnowledgeBase.load(List.of(kb)));
        assertThat(items).extracting(i -> i.type() + " " + i.key()).contains(
                "link service:orders -deployed-as-> deployment:orders", "link service:billing -deployed-as-> deployment:billing",
                "flow settlement");

        assertThat(review.decide("link", "service:billing -deployed-as-> deployment:billing", "prod", false))
                .isEqualTo("rejected: service:billing -deployed-as-> deployment:billing");
        assertThat(review.decide("link", "service:orders -deployed-as-> deployment:orders", "prod", true)).startsWith("approved");
        assertThat(review.decide("flow", "settlement", null, true)).isEqualTo("approved: settlement");
        assertThat(review.decide("link", "service:x -deployed-as-> deployment:y", null, true)).startsWith("no such link");

        assertThat(Files.readString(kb.resolve("knowledge.yml"))).as("the people's file untouched").isEqualTo("# people's notes\nabbreviations:\n  ord: order\n");
        assertThat(Files.readString(kb.resolve(KbReview.FILE))).contains("decision: reject", "by: jane", "evidence: shop/charts/billing/deployment.yaml",
                "settlement:", "approvedBy: jane");
        assertThat(Files.readString(kb.resolve(Proposals.FILE))).doesNotContain("settlement:");

        KnowledgeBase after = KnowledgeBase.load(List.of(kb));
        assertThat(after.neighbors("service:billing", "out", List.of("deployed-as"), 10)).doesNotContain("deployed-as");
        assertThat(after.neighbors("service:orders", "out", List.of("deployed-as"), 10)).contains("decision=approved by jane");
        assertThat(after.flows(null)).contains("- settlement\n").doesNotContain("proposed");
        assertThat(KbReview.items(after)).as("nothing left").isEmpty();
    }

    @Test
    void thePageServesItemsAndTakesDecisionsOnLoopback() throws Exception {
        Path kb = kb();
        KbReview review = new KbReview(() -> KnowledgeBase.load(KnowledgeBase.factFiles(List.of(kb))), kb.resolve(KbReview.FILE),
                kb.resolve(Proposals.FILE), "sam");
        String url = review.start(0);
        try {
            assertThat(url).startsWith("http://127.0.0.1:");
            HttpClient http = HttpClient.newHttpClient();
            assertThat(http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString()).body())
                    .contains("<title>Knowledge review</title>");
            assertThat(http.send(HttpRequest.newBuilder(URI.create(url + "items")).build(), HttpResponse.BodyHandlers.ofString()).body())
                    .contains("\"type\":\"flow\"", "settlement");
            String r = http.send(HttpRequest.newBuilder(URI.create(url + "decide")).POST(HttpRequest.BodyPublishers.ofString(
                    "{\"type\":\"flow\",\"key\":\"settlement\",\"decision\":\"reject\"}")).build(), HttpResponse.BodyHandlers.ofString()).body();
            assertThat(r).contains("rejected: settlement");
            assertThat(Files.readString(kb.resolve(Proposals.FILE))).doesNotContain("settlement:");
        } finally {
            review.stop();
        }
    }
}
