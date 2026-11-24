package dev.logast;

import dev.logast.policy.Policy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceSetupTest {
    @Test
    void identityForTheSameValueHint() {
        assertThat(VocabularyHarvest.identity("orderId")).isEqualTo("order#id");
        assertThat(VocabularyHarvest.identity("order_uuid")).isEqualTo("order#id");
        assertThat(VocabularyHarvest.identity("order.id")).isEqualTo("order#id");
        assertThat(VocabularyHarvest.identity("ORDER_ID")).isEqualTo("order#id");
        assertThat(VocabularyHarvest.identity("activeShiftId")).isEqualTo("active.shift#id");
        assertThat(VocabularyHarvest.identity("tenant")).isEqualTo("tenant#id");
        assertThat(VocabularyHarvest.identity("supplierNumber")).isEqualTo("supplier#number");
        assertThat(VocabularyHarvest.identity("supplier_no")).isEqualTo("supplier#number");
    }

    /** #86: the harvest only collects. One active term per key as the code spells it; grouping is a hint, never applied. */
    @Test
    void harvestsOneTermPerKeyAsWrittenAndOnlyHintsAtGroups(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("A.java"), """
                class A {
                    void a(String orderId, String tableId, long amountCents, String userId, String openedByUserId) {
                        MDC.put("orderId", orderId);
                        MDC.put("acmeWeek", week);
                        try (var s = MdcScope.of("tenant.id", t, "shiftId", s)) { }
                        log.info("Order placed [order.id={}, table_id={}, amount.cents={}]", orderId, tableId, amountCents);
                        log.warn("Payment failed, orderUuid=" + orderId + " amountCents={}", amountCents);
                        log.info("Delivery booked [supplierId={}, supplierNumber={}]", supplierId, supplierNumber);
                        log.info("Table opened [userId={}, openedByUserId={}]", userId, openedByUserId);
                        log.info("Shift {} closed", shift(activeShiftId));
                    }
                    void b(String tableId) {} void c(String tableId) {}
                    void d(String deviceId) {}
                }""");
        VocabularyHarvest.Result r = VocabularyHarvest.run(dir, null, dev.logast.policy.KeyStyle.DOTTED, 3);
        var byKey = new java.util.HashMap<String, VocabularyHarvest.Term>();
        r.terms().forEach(t -> byKey.put(t.key(), t));
        assertThat(byKey).containsOnlyKeys("orderId", "acmeWeek", "tenant.id", "shiftId", "order.id", "table_id", "orderUuid",
                "supplierId", "supplierNumber", "userId", "openedByUserId");
        assertThat(byKey.get("orderId").sameAs()).containsExactly("order.id", "orderUuid");
        assertThat(byKey.get("orderId").params()).containsExactly("orderId");
        assertThat(byKey.get("acmeWeek").sameAs()).isEmpty();
        assertThat(byKey.get("supplierId").sameAs()).as("an id and a number are never the same").isEmpty();
        assertThat(byKey.get("supplierNumber").sameAs()).isEmpty();
        assertThat(byKey.get("openedByUserId").kindOf()).isEqualTo("userId");
        assertThat(byKey.get("openedByUserId").sameAs()).isEmpty();
        assertThat(byKey.get("table_id").sameAs()).containsExactly("tableId");     // declared-only keys count for hints
        assertThat(r.unused()).extracting(VocabularyHarvest.Term::key).containsExactly("tableId");
        assertThat(r.unused().getFirst().sameAs()).containsExactly("table_id");
        assertThat(byKey).doesNotContainKey("deviceId");                      // declared once, never logged
        assertThat(r.valueKeys()).anySatisfy(v -> assertThat(v.spellings()).containsKeys("amount.cents", "amountCents"));

        String yaml = VocabularyHarvest.yaml(r);
        assertThat(yaml).contains("""
                  acmeWeek:""").contains("""
                    mdc: { key: acmeWeek }
                """).contains("""
                    mdc: { key: orderId, sources: { params: [orderId] } }
                """).contains("""
                    mdc: { key: tenant.id }
                """).containsPattern("(?m)^  orderUuid: +# 0 / 1 / 0, 1 file; same value as orderId, order.id\\?$")
                .containsPattern("(?m)^  openedByUserId: +# 0 / 1 / 1, 1 file; a kind of userId\\?$")
                .containsPattern("(?m)^  supplierNumber: +# 0 / 1 / 0, 1 file$")
                .contains("#  tableId: { mdc: { key: tableId, sources: { params: [tableId] } } }   # 0 / 0 / 3, 1 file; same value as table_id?")
                .doesNotContain("aliases: [orderUuid").doesNotContain("\"acme");
        Files.writeString(dir.resolve("logast.yaml"), yaml);
        Policy loaded = Policy.resolve(dir, null);
        assertThat(loaded.warnings()).as("the generated file loads cleanly").isEmpty();
        assertThat(loaded.terms()).containsAll(byKey.keySet()).doesNotContain("order", "supplier", "acme.week", "tableId");

        // the same file, uncommented, still loads: every hint and every unused line is valid YAML once the "#" is gone
        Files.writeString(dir.resolve("logast.yaml"), yaml.replaceAll("(?m)^#  (\\w+: \\{ mdc)", "  $1"));
        assertThat(Policy.resolve(dir, null).terms()).contains("tableId");

        // a policy that already has a key or term leaves it out
        Files.writeString(dir.resolve("logast.yaml"), """
                logging: { keyStyle: camel }
                vocabulary:
                  order: { mdc: { key: orderId, aliases: [orderUuid] } }
                  tenant.id: { }
                """);
        var known = Policy.resolve(dir, null);
        var again = VocabularyHarvest.run(dir, known, 3).terms();
        assertThat(again).extracting(VocabularyHarvest.Term::key).doesNotContain("orderId", "orderUuid", "tenant.id").contains("order.id", "shiftId");
        assertThat(again).filteredOn(t -> t.key().equals("order.id")).singleElement()
                .satisfies(t -> assertThat(t.sameAs()).as("the policy's key counts for the hint").containsExactly("orderId"));
    }

    @Test
    void quotesKeysOnlyWhenYamlNeedsIt(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("A.java"), """
                class A {
                    void a() {
                        MDC.put("on", x);
                        MDC.put("Order-Id", y);
                    }
                }""");
        String yaml = VocabularyHarvest.yaml(VocabularyHarvest.run(dir, null, 3));
        assertThat(yaml).contains("  \"on\":").contains("key: \"on\" }").contains("  Order-Id:").contains("key: Order-Id }");
        Files.writeString(dir.resolve("logast.yaml"), yaml);
        assertThat(Policy.resolve(dir, null).terms()).contains("on", "Order-Id");
    }

    @Test
    void suggestsVocabularyFromFixture() {
        var keys = VocabularyHarvest.run(Fixtures.root(), null, 3).terms().stream().map(VocabularyHarvest.Term::key).toList();
        assertThat(keys).isNotEmpty();
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
        assertThat(p.reporting().sourceComments()).as("off by default (#72)").isFalse();
        assertThat(yaml).as("written out, so a changed default never changes the service's trust").contains("entrypoints:\n  trustInboundTrace: true\n");
        assertThat(p.entrypoints().trustInboundTrace()).isTrue();

        Files.writeString(dir.resolve("logast.yaml"), "reporting:\n  sourceComments: true\n");
        assertThat(Policy.resolve(dir, null).reporting().sourceComments()).isTrue();

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
        assertThat(order.key()).as("as written, not restyled (#86)").isEqualTo("order.id");
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

    /** #236: apply's first-run template keeps a pinned fleet policy version (the rollout pins it before apply runs). */
    @org.junit.jupiter.api.Test
    void theTemplateKeepsAPinnedPolicyVersion() {
        org.assertj.core.api.Assertions.assertThat(ServiceSetup.extendsYaml(java.util.List.of("fleet:shop@policy-3", "../area.yaml")))
                .contains("  - fleet:shop@policy-3    # = ").contains("  - ../area.yaml");
    }
}
