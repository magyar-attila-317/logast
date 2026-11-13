package dev.logast;

import dev.logast.policy.Policy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceSetupTest {
    @Test
    void termFromKey() {
        assertThat(VocabularyHarvest.term("orderId")).isEqualTo("order");
        assertThat(VocabularyHarvest.term("order_uuid")).isEqualTo("order");
        assertThat(VocabularyHarvest.term("order.id")).isEqualTo("order");
        assertThat(VocabularyHarvest.term("activeShiftId")).isEqualTo("active.shift");
        assertThat(VocabularyHarvest.term("tenant")).isEqualTo("tenant");
    }

    @Test
    void harvestsFromMdcLogKeysAndDeclarations(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("A.java"), """
                class A {
                    void a(String orderId, String tableId, long amountCents) {
                        MDC.put("orderId", orderId);
                        try (var s = MdcScope.of("tenant.id", t, "shiftId", s)) { }
                        log.info("Order placed [order.id={}, table.id={}, amount.cents={}]", orderId, tableId, amountCents);
                        log.warn("Payment failed, orderUuid=" + orderId + " amountCents={}", amountCents);
                        log.info("Shift {} closed", shift(activeShiftId));
                    }
                    void b(String tableId) {} void c(String tableId) {}
                    void d(String deviceId) {}
                }""");
        VocabularyHarvest.Result r = VocabularyHarvest.run(dir, null, dev.logast.policy.KeyStyle.DOTTED, 3);
        var byName = new java.util.HashMap<String, VocabularyHarvest.Term>();
        r.terms().forEach(t -> byName.put(t.name(), t));
        assertThat(byName.get("order").key()).isEqualTo("order.id");
        assertThat(byName.get("order").aliases()).containsExactlyInAnyOrder("orderId", "orderUuid");
        assertThat(byName.get("order").params()).containsExactly("orderId");
        assertThat(byName.get("tenant").key()).isEqualTo("tenant.id");
        assertThat(byName.get("shift").key()).isEqualTo("shift.id");
        assertThat(byName.get("table").aliases()).containsExactly("tableId");
        assertThat(byName).doesNotContainKey("device");                     // declared once, never logged
        assertThat(r.valueKeys()).anySatisfy(v -> assertThat(v.spellings()).containsKeys("amount.cents", "amountCents"));

        // a policy that already maps orderId and defines tenant leaves those out
        Files.writeString(dir.resolve("logast.yaml"), """
                vocabulary:
                  order: { mdc: { key: order.id, aliases: [orderId, orderUuid] } }
                  tenant: { mdc: { key: tenant.id } }
                """);
        var known = dev.logast.policy.Policy.resolve(dir, null);
        var names = VocabularyHarvest.run(dir, known, 3).terms().stream().map(VocabularyHarvest.Term::name).toList();
        assertThat(names).doesNotContain("order", "tenant").contains("table", "shift");

        String yaml = VocabularyHarvest.yaml(r);
        Files.writeString(dir.resolve("logast.yaml"), yaml);
        assertThat(dev.logast.policy.Policy.resolve(dir, null).terms()).contains("order", "table", "tenant");
    }

    @Test
    void suggestsVocabularyFromFixture() {
        var names = VocabularyHarvest.run(Fixtures.root(), null, 3).terms().stream().map(VocabularyHarvest.Term::name).toList();
        assertThat(names).contains("tenant", "order");
    }

    @Test
    void templateIsAValidPolicyAndSuggestionsUncommentCleanly(@TempDir Path dir) throws Exception {
        String vocab = """
                vocabulary:
                  tenant:      # 1 / 0 / 0, 1 file
                    mdc: { key: tenant.id, aliases: [tenantId] }
                  order:
                    mdc: { key: order.id, aliases: [orderId] }
                """;
        String yaml = ServiceSetup.policyTemplate(List.of(), List.of("logging"), true, null);
        Files.writeString(dir.resolve("logast.yaml"), yaml);
        Policy p = Policy.resolve(dir, null);
        assertThat(p.packs()).containsExactly("logging");
        assertThat(p.logging().rewriteExistingMessages()).isFalse();

        Files.writeString(dir.resolve("logast.yaml"), ServiceSetup.policyTemplate(List.of(), List.of("logging"), true, vocab));
        Policy q = Policy.resolve(dir, null);
        assertThat(q.terms()).contains("tenant", "order");
    }

    @Test
    void gitignoreKeepsSharedFilesAndIsIdempotent(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(".gitignore"), "build/\n.logast/\n");
        assertThat(ServiceSetup.updateGitignore(dir)).containsExactlyElementsOf(ServiceSetup.GITIGNORE);
        assertThat(ServiceSetup.updateGitignore(dir)).isEmpty();
        assertThat(Files.readString(dir.resolve(".gitignore"))).contains("build/").doesNotContain(".logast/\n");
    }

    @Test
    void keyStyles() {
        var camel = dev.logast.policy.KeyStyle.parse("camelCase");
        assertThat(camel.apply("order.id")).isEqualTo("orderId");
        assertThat(camel.apply("order_item_id")).isEqualTo("orderItemId");
        assertThat(camel.apply("orderID")).isEqualTo("orderId");
        assertThat(dev.logast.policy.KeyStyle.parse("PascalCase").apply("order.id")).isEqualTo("OrderId");
        assertThat(dev.logast.policy.KeyStyle.parse("snake").apply("orderItemId")).isEqualTo("order_item_id");
        assertThat(dev.logast.policy.KeyStyle.parse("dotted").apply("amountCents")).isEqualTo("amount.cents");
        assertThat(dev.logast.policy.KeyStyle.parse("keep").apply("Weird_key")).isEqualTo("Weird_key");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> dev.logast.policy.KeyStyle.parse("kebab"))
                .hasMessageContaining("camel");
    }

    @Test
    void camelStyleHarvestAndMdcRename(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("A.java"), """
                class A {
                    void a(String orderId) {
                        MDC.put("order.id", orderId);
                        log.info("Placed [order.id={}]", orderId);
                    }
                }""");
        Files.writeString(dir.resolve("logast.yaml"), "logging: { keyStyle: camel }\n");
        var policy = dev.logast.policy.Policy.resolve(dir, null);
        var order = VocabularyHarvest.run(dir, policy, 3).terms().getFirst();
        assertThat(order.key()).isEqualTo("orderId");
        assertThat(order.aliases()).containsExactly("order.id");
        assertThat(policy.canonicalMdcKey("job.name")).isEqualTo("jobName");
        assertThat(policy.canonicalMdcKey("traceId")).isNull();
        assertThat(policy.canonicalMdcKey("jobName")).isNull();
    }

    @Test
    void detectsTheKeyStyleTheCodeUses(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("A.java"), """
                class A {
                    void a(String orderId, String tableId) {
                        MDC.put("orderId", orderId);
                        log.info("Order placed [tableId={}, order.id={}]", tableId, orderId);
                        log.info("Table freed [tableId={}]", tableId);
                    }
                }""");
        assertThat(VocabularyHarvest.detectStyle(dir)).isEqualTo(dev.logast.policy.KeyStyle.CAMEL);
        assertThat(ServiceSetup.policyTemplate(List.of(), List.of(), false, null, null)).contains("  keyStyle: camel\n");
    }

    @Test
    void detectsTheJsonLogFormatTheServiceAlreadyUses(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("build.gradle.kts"), "dependencies { implementation(\"org.springframework.boot:spring-boot-starter\") }\n");
        assertThat(ServiceSetup.detectLogFormat(dir)).isNull();
        Files.writeString(dir.resolve("build.gradle.kts"),
                "dependencies { implementation(\"net.logstash.logback:logstash-logback-encoder:8.0\") }\n");
        assertThat(ServiceSetup.detectLogFormat(dir)).isEqualTo("logstash");
        Path res = Files.createDirectories(dir.resolve("src/main/resources"));
        Files.writeString(res.resolve("application.yml"), "logging:\n  structured:\n    format:\n      console: gelf\n");
        assertThat(ServiceSetup.detectLogFormat(dir)).isEqualTo("gelf");          // what the config says wins
        assertThat(ServiceSetup.policyTemplate(java.util.List.of(), java.util.List.of(), false, null, null, "logstash", null))
                .contains("structuredFormat: logstash");
    }

    @Test
    void logastYmlIsReadToo(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("logast.yml"), "logging:\n  structuredFormat: logstash\n");
        assertThat(dev.logast.policy.Policy.resolve(dir, null).logging().structuredFormat()).isEqualTo("logstash");
    }
}
