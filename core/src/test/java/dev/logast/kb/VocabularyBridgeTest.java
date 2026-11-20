package dev.logast.kb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** #170: one vocabulary for logs and the knowledge base. */
class VocabularyBridgeTest {
    @TempDir
    Path tmp;

    private KnowledgeBase kb() throws IOException {
        Files.writeString(tmp.resolve("facts.jsonl"), """
                {"header":{"schema":"kb-facts/0","extractor":"java","repo":"shop"}}
                {"node":{"id":"table:billing.ord_settlement_batch","kind":"table","attrs":{"columns":["id bigint","settlement_run_id bigint","ord_ref text"]},"evidence":{"file":"V1.sql"}}}
                {"edge":{"from":"job:billing:Sync.run","kind":"writes","to":"table:billing.settlement_run","provenance":"extracted","evidence":{"file":"Sync.java","line":3}}}
                {"edge":{"from":"call:orders:kafka orders.placed","kind":"publishes","to":"topic:orders.placed","provenance":"extracted","evidence":{"file":"O.java","line":4}}}
                """);
        Files.writeString(tmp.resolve("knowledge.yml"), """
                glossary:
                  order:
                    definition: A customer's request to buy.
                    aliases: [purchase, orders]
                  invoice: The bill for an order.
                abbreviations:
                  ord: order
                  ref: reference
                """);
        return KnowledgeBase.load(List.of(tmp));
    }

    @Test
    void theGlossaryBecomesAVocabularyBlock() throws IOException {
        Knowledge k = kb().knowledge();
        assertThat(k.vocabularyYaml(Map.of())).isEqualTo("""
                vocabulary:
                  order:   # A customer's request to buy.
                    aliases: [purchase, orders]
                  invoice:   # The bill for an order.
                """);
        assertThat(k.vocabularyYaml(Map.of("order", Set.of("purchase"), "invoice", Set.of()))).as("only what company.yaml lacks")
                .isEqualTo("vocabulary:\n  order:   # A customer's request to buy.\n    aliases: [orders]\n");
        assertThat(k.vocabularyYaml(Map.of("order", Set.of("purchase", "orders"), "invoice", Set.of())))
                .startsWith("# every glossary term is in the vocabulary already");
    }

    @Test
    void wordsTheGlossaryDoesNotKnow() throws IOException {
        String unknown = kb().unknownTerms(10);
        assertThat(unknown).startsWith("Words the glossary does not know yet").contains("- settlement (3):", "- run (2):", "- batch (1):", "- placed (1): topic:orders.placed");
        assertThat(unknown).as("known: terms, aliases, abbreviations, and common words")
                .doesNotContain("- ord ", "- order", "- orders", "- ref ", "- id ", "- bigint");
    }
}
