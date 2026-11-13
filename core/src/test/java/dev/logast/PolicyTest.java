package dev.logast;

import dev.logast.policy.Policy;
import dev.logast.rules.Phrasing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyTest {
    @TempDir
    Path dir;

    @Test
    void defaultsAreOpinionated() {
        Policy p = Policy.defaults();
        assertThat(p.packs()).containsExactly("logging", "tracing");
        assertThat(p.mdc().ambientKeys()).containsExactly("traceId", "spanId");
        assertThat(p.phrasing().events()).containsKeys("started", "completed", "failed");
        assertThat(p.levels().clientErrors()).contains("org.springframework.security.access.AccessDeniedException");
    }

    @Test
    void layersMergeAppendAndRecordOverrides() throws IOException {
        Files.writeString(dir.resolve("company.yaml"), """
                pii: [loyaltyCardId]
                vocabulary:
                  order:
                    aliases: [purchase]
                    mdc: { key: order.id, aliases: [orderId], sources: { params: [orderId] } }
                  ticket:
                    aliases: [chit]
                logging:
                  swallowedLevel: error
                """);
        Files.writeString(dir.resolve("logast.yaml"), """
                extends: [company.yaml]
                packs: [logging, metrics]
                vocabulary:
                  order:
                    aliases: [check]
                  ticket:
                    override: true
                    aliases: [docket]
                logging:
                  swallowedLevel: warn
                areas:
                  kds:
                    packages: [com.x.kitchen]
                    vocabulary:
                      ticket: { aliases: [order] }
                """);
        Policy p = Policy.resolve(dir, null);
        assertThat(p.pii()).contains("password", "loyaltyCardId");                    // lists append across layers
        assertThat(p.packs()).containsExactly("logging", "metrics");                    // selections replace
        assertThat(p.vocabulary().get("order").aliases()).containsExactly("purchase", "check");
        assertThat(p.vocabulary().get("ticket").aliases()).containsExactly("docket");   // override replaced
        assertThat(p.logging().swallowedLevel()).isEqualTo("warn");
        assertThat(p.overrides()).anyMatch(o -> o.contains("overrides vocabulary.ticket"))
                .anyMatch(o -> o.contains("logging.swallowedLevel") && o.contains("company.yaml"));
        // default keyStyle camel: the company layer's "order.id" is used as orderId, its spelling becomes an alias
        assertThat(p.mdcAliases()).containsEntry("order.id", "orderId");
        assertThat(p.synonyms(null)).containsEntry("purchase", "order").containsEntry("check", "order");
        assertThat(p.synonyms("kds")).containsEntry("order", "ticket");                 // area meaning wins
        assertThat(p.areaOf("com.x.kitchen.KdsService")).isEqualTo("kds");
        assertThat(p.areaOf("com.x.billing.Invoice")).isNull();
    }

    @Test
    void phrasingRewritesVariantsAndKeepsTags() {
        Phrasing ph = new Phrasing(Policy.defaults());
        assertThat(ph.rewrite("Starting reconciliation")).contains("Reconciliation started");
        assertThat(ph.rewrite("Failed to charge card {}")).contains("Charge card {} failed");
        assertThat(ph.rewrite("Error while syncing menu")).contains("Syncing menu failed");
        assertThat(ph.rewrite("KDS_PRINT: could not build slip")).contains("KDS_PRINT: Build slip failed");
        assertThat(ph.rewrite("[PIN-LOGIN] finished sweep")).contains("[PIN-LOGIN] Sweep completed");
        assertThat(ph.rewrite("Order sync failed")).isEmpty();           // already canonical
        assertThat(ph.rewrite("Order created")).isEmpty();               // not a lifecycle message
        assertThat(ph.matches("Payment charge failed, reconciliation will retry", "failed")).isTrue();
        assertThat(ph.matches("Could not charge payment", "failed")).isFalse();
    }

    @Test
    void piiMatchesWholeWordsOnly() {
        var d = new dev.logast.rules.DomainValues(Policy.defaults());
        assertThat(d.isPiiName("customerEmail")).isTrue();
        assertThat(d.isPiiName("card_number")).isTrue();
        assertThat(d.isPiiName("customerCardNumber")).isTrue();
        assertThat(d.isPiiName("shippingReason")).isFalse();   // "pin" is not a word here
        assertThat(d.isPiiName("reason")).isFalse();
        assertThat(d.isPiiName("tokenBucket")).isTrue();        // conservative: "token" is a word
    }
}
