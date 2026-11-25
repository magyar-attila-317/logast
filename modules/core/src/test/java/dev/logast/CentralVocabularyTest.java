package dev.logast;

import dev.logast.policy.Policy;
import dev.logast.policy.PolicyLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** #113: the central worksheet keeps the owner's decisions across runs; its summary; company.yaml; prune. */
class CentralVocabularyTest {
    @TempDir
    Path tmp;

    private static CentralVocabulary.Use use(String key, String service, String value, String from, String type) {
        return new CentralVocabulary.Use(key, service, "src/main/java/A.java", 3, "mdc", value, from, type,
                from == null ? null : "com.example." + service + "." + from.substring(0, from.indexOf('.')),
                from == null ? null : from.substring(from.indexOf('.') + 1));
    }

    private static Path service(Path dir, String file, String text) throws IOException {
        Path f = dir.resolve("src/main/java").resolve(file);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
        return dir;
    }

    @Test
    void decisionsSurviveTheNextRunAndOnlyDecidedTermsArePublished() throws IOException {
        Path a = service(tmp.resolve("billing"), "com/example/billing/Desk.java", """
                package com.example.billing;
                class Desk {
                    void issue(Long invoiceId, String batchId) {
                        org.slf4j.MDC.put("invoiceId", String.valueOf(invoiceId));
                        log.info("Issued [batchId={}]", batchId);
                    }
                }
                """);
        Path b = service(tmp.resolve("payments"), "com/example/payments/Pay.java", """
                package com.example.payments;
                class Pay {
                    void pay(Long invoiceId) {
                        log.info("Paid [invoice_id={}]", invoiceId);
                    }
                }
                """);
        Path central = tmp.resolve("central");
        Map<String, Path> services = Map.of("billing", a, "payments", b);
        CentralVocabulary.Result first = CentralVocabulary.run(central, services);
        assertThat(first.summary()).contains("to decide: 3 (3 new): batchId (new), invoiceId (new), invoice_id (new)");
        assertThat(first.worksheet()).contains("invoice_id: same type Long; differs: services billing vs payments")
                .as("#139: the choices as lines to uncomment, the suggestion first")
                .contains("  batchId:\n    # local: true   # decide: suggested, only billing uses it: not published\n"
                        + "    # term: batch   # decide: a company-wide term; batchId is its key in logs\n")
                .startsWith("# logast vocabulary worksheet");
        assertThat(Files.readString(central.resolve(CentralVocabulary.WORKSHEET))).as("#321: the written file names its schema")
                .startsWith("# $schema: schemas/vocabulary-worksheet.schema.json\n"
                        + "# yaml-language-server: $schema=schemas/vocabulary-worksheet.schema.json\n");
        assertThat(central.resolve("schemas/vocabulary-worksheet.schema.json")).as("the schema beside the worksheet").isRegularFile();
        assertThat(first.terms()).isZero();

        Path ws = central.resolve(CentralVocabulary.WORKSHEET);
        // deciding = uncommenting one of the lines logast wrote (#139), or writing one
        String text = Files.readString(ws);
        String termLine = text.lines().filter(l -> l.startsWith("    # term: invoice ")).findFirst().orElseThrow();
        Files.writeString(ws, text.replace(termLine, "    term: invoice")
                .replace("  invoice_id:\n", "  invoice_id:\n    same_as: invoiceId\n"));
        CentralVocabulary.Result second = CentralVocabulary.run(central, services);
        assertThat(second.worksheet()).as("decisions kept").contains("    term: invoice\n", "    same_as: invoiceId\n");
        assertThat(second.summary()).contains("to decide: 1 (0 new): batchId")
                .contains("  invoice_id (same_as invoiceId) still in payments: apply renames it in services that extend company.yaml");
        Path company = central.resolve(CentralVocabulary.COMPANY);
        assertThat(Files.readString(company)).startsWith("# $schema: schemas/logast.schema.json\n").contains("\n# >>> logast").contains("# GENERATED").contains("invoice:\n", "key: invoiceId", "aliases: [invoice_id]",
                "params: [invoiceId]").doesNotContain("batchId");

        // a valid policy layer: a service that extends it gets the alias
        Files.writeString(tmp.resolve("logast.yaml"), "extends: [central/company.yaml]\n");
        Policy p = PolicyLoader.load(tmp, null);
        assertThat(p.mdcAliases()).containsEntry("invoice_id", "invoiceId");

        // the third run: nobody uses invoice any more, the decision stays and is listed
        Files.writeString(a.resolve("src/main/java/com/example/billing/Desk.java"), "class Desk {}\n");
        Files.writeString(b.resolve("src/main/java/com/example/payments/Pay.java"), "class Pay {}\n");
        CentralVocabulary.Result third = CentralVocabulary.run(central, services);
        assertThat(third.summary()).contains("decided, no service uses it any more: term invoice (invoiceId), invoice_id (same_as invoiceId)")
                .contains("to decide: -");
        assertThat(third.worksheet()).contains("  invoiceId:\n    term: invoice\n").doesNotContain("batchId");
        assertThat(Files.readString(company)).as("still published").contains("aliases: [invoice_id]");
    }

    /** #121: a term decided with an area goes under areas.<area>.vocabulary; the result is a policy a service extends. */
    @Test
    void aTermDecidedWithAnAreaIsPublishedUnderThatArea() throws IOException {
        Path a = service(tmp.resolve("billing"), "com/example/billing/Desk.java", """
                package com.example.billing;
                class Desk {
                    void issue(Long invoiceId, Long batchId) {
                        org.slf4j.MDC.put("invoiceId", String.valueOf(invoiceId));
                        log.info("Issued [batchId={}]", batchId);
                    }
                }
                """);
        Files.writeString(a.resolve("logast.yaml"), """
                areas:
                  counter:
                    packages: [com.example.billing.counter]
                    vocabulary:
                      till: { aliases: [register] }
                    requiredMdc: [tillId]
                """);
        Path b = service(tmp.resolve("payments"), "com/example/payments/Pay.java", """
                package com.example.payments;
                class Pay {
                    void pay(Long invoiceId, Long batchId) {
                        org.slf4j.MDC.put("invoice_id", String.valueOf(invoiceId));
                        log.info("Paid [batchId={}]", batchId);
                    }
                }
                """);
        Path central = tmp.resolve("central");
        Map<String, Path> services = Map.of("billing", a, "payments", b);
        CentralVocabulary.Result first = CentralVocabulary.run(central, services);
        assertThat(first.worksheet()).as("the areas the services' policies know")
                .contains("areas:\n  counter:\n    known_in:\n      - {service: \"billing\", packages: [com.example.billing.counter], "
                        + "vocabulary: [till], requiredMdc: [tillId]}\n");
        assertThat(first.summary()).contains("areas: counter (0 terms; in the logast.yaml of billing)");

        Path ws = central.resolve(CentralVocabulary.WORKSHEET);
        Files.writeString(ws, Files.readString(ws)
                .replace("areas:\n", "areas:\n  invoicing:\n    packages: [com.example.billing, com.example.payments]\n")
                .replace("  invoiceId:\n", "  invoiceId:\n    term: invoice\n    area: invoicing\n    owner: \"billing team\"\n")
                .replace("  invoice_id:\n", "  invoice_id:\n    same_as: invoiceId\n")
                .replace("  batchId:\n", "  batchId:\n    term: batch\n"));
        CentralVocabulary.Result second = CentralVocabulary.run(central, services);
        assertThat(second.worksheet()).as("decisions kept").contains("    area: invoicing\n")
                .contains("  invoicing:\n    packages: [com.example.billing, com.example.payments]\n    terms: [invoice]\n");
        assertThat(second.summary()).contains("areas: counter (0 terms; in the logast.yaml of billing), invoicing (1 term)");
        assertThat(Files.readString(central.resolve(CentralVocabulary.COMPANY))).contains("""
                vocabulary:
                  batch:
                    mdc:
                      key: batchId
                      sources:
                        params: [batchId]
                areas:
                  invoicing:
                    packages: [com.example.billing, com.example.payments]
                    vocabulary:
                      invoice:   # owner: billing team
                        mdc:
                          key: invoiceId
                          aliases: [invoice_id]
                """);

        // a valid policy layer a service extends, adding its own term to the area; the alias is still renamed
        Path svc = tmp.resolve("svc");
        Files.createDirectories(svc);
        Files.writeString(svc.resolve("logast.yaml"), """
                extends: [../central/company.yaml]
                vocabulary:
                  invoiceId:                     # harvested before company.yaml had it
                    mdc: { key: invoiceId }
                areas:
                  invoicing:
                    vocabulary:
                      receipt: { mdc: { key: receiptId } }
                """);
        Policy p = PolicyLoader.load(svc, null);
        assertThat(p.vocabulary()).containsOnlyKeys("batch", "invoiceId");
        assertThat(p.areas().get("invoicing").packages()).containsExactly("com.example.billing", "com.example.payments");
        assertThat(p.areas().get("invoicing").vocabulary()).containsOnlyKeys("invoice", "receipt");
        assertThat(p.areaOf("com.example.payments.Pay")).isEqualTo("invoicing");
        assertThat(p.mdcAliases()).as("MDC_KEY_ALIAS renames an area term's aliases").containsEntry("invoice_id", "invoiceId");
        assertThat(p.canonicalMdcKey("invoice_id")).isEqualTo("invoiceId");
        assertThat(p.mdcRegistry()).containsKeys("invoiceId", "receiptId", "batchId");
        assertThat(VocabularyPrune.check(svc).covered()).as("an area term covers the service's own MDC entry")
                .extracting(c -> c.term() + "->" + c.by()).containsExactly("invoiceId->invoice in area invoicing");

        // an area belongs next to a term
        Files.writeString(ws, Files.readString(ws).replace("    term: batch\n", "    area: invoicing\n"));
        assertThat(CentralVocabulary.run(central, services).summary())
                .contains("  batchId: area invoicing without term: an area goes next to term: (its same_as keys follow it)");
    }

    @Test
    void theSummaryNamesDecisionsThatCannotBePublished() {
        Map<String, CentralVocabulary.Decision> d = Map.of(
                "parcelId", new CentralVocabulary.Decision(null, "parcel_id", false, null),
                "parcel_id", new CentralVocabulary.Decision(null, null, false, null),
                "routeId", new CentralVocabulary.Decision(null, "nowhere", false, null),
                "a", new CentralVocabulary.Decision("x", null, false, null),
                "b", new CentralVocabulary.Decision("x", null, false, null),
                "depotId", new CentralVocabulary.Decision(null, null, true, null));
        List<CentralVocabulary.Use> uses = List.of(use("parcelId", "s1", "p.id()", "Parcel.id()", "Long"),
                use("parcel_id", "s2", "p.id()", "Parcel.id()", "Long"), use("routeId", "s1", "routeId", null, null),
                use("depotId", "s1", "depotId", null, null), use("depotId", "s2", "depotId", null, null));
        CentralVocabulary.Result r = CentralVocabulary.build(uses, List.of("s1", "s2"), new CentralVocabulary.Previous(d, d.keySet()));
        assertThat(r.summary()).contains(
                "  parcelId: same_as parcel_id, which has no term yet: give parcel_id a term: to publish both",
                "  routeId: same_as nowhere names no key of this worksheet",
                "  term x is decided for both a and b; one key per term (make the other same_as a)",
                "  depotId (local) is used by 2 services: s1, s2");
        assertThat(r.terms()).as("nothing publishable").isZero();
        assertThat(r.company()).contains("vocabulary: {}");
    }

    @Test
    void valueTypesComeFromDeclarations() throws IOException {
        Path s = service(tmp.resolve("s"), "com/example/s/Parcel.java", """
                package com.example.s;
                public record Parcel(String code, long weight) {
                    public java.util.UUID trackingId() { return null; }
                }
                """);
        service(s, "com/example/s/Desk.java", """
                package com.example.s;
                class Desk {
                    void run(Parcel parcel, int routeId) {
                        org.slf4j.MDC.put("parcelId", parcel.code());
                        org.slf4j.MDC.put("trackingId", parcel.trackingId().toString());
                        log.info("Routed [routeId={}, parcelNo={}]", routeId, parcel.weight());
                        log.atInfo().addKeyValue("weightNo", parcel.weight()).log("x");
                    }
                }
                """);
        List<CentralVocabulary.Use> uses = CentralVocabulary.uses(s, "s");
        assertThat(uses).extracting(u -> u.key() + " " + u.kind() + " " + u.from() + " " + u.type() + " " + u.typeFqn() + " :" + u.line())
                .containsExactlyInAnyOrder(
                        "parcelId mdc Parcel.code() String com.example.s.Parcel :4",
                        "trackingId mdc Parcel.trackingId() UUID com.example.s.Parcel :5",
                        "weightNo addKeyValue Parcel.weight() Long com.example.s.Parcel :7",
                        "routeId kv null Integer null :6",
                        "parcelNo kv Parcel.weight() Long com.example.s.Parcel :6");
    }

    @Test
    void pruneRemovesOnlyEntriesTheParentCoversInFull() throws IOException {
        Files.writeString(tmp.resolve("company.yaml"), """
                vocabulary:
                  order:
                    mdc: { key: orderId, aliases: [order_id], sources: { params: [orderId] } }
                  customer:
                    mdc: { key: customerId }
                """);
        Path svc = tmp.resolve("svc");
        Files.createDirectories(svc);
        Files.writeString(svc.resolve("logast.yaml"), """
                extends: [../company.yaml]
                vocabulary:
                  # harvested
                  orderId:
                    mdc: { key: orderId, sources: { params: [orderId] } }
                  order_id: { mdc: { key: order_id } }
                  customer:
                    mdc:
                      key: customerId
                      sources: { params: [clientId] }   # a real difference: stays
                  shelfId:
                    mdc: { key: shelfId }
                logging:
                  keyStyle: camel
                """);
        VocabularyPrune.Report r = VocabularyPrune.check(svc);
        assertThat(r.covered()).extracting(c -> c.term() + "->" + c.by()).containsExactly("orderId->order", "order_id->order");
        assertThat(r.differs()).extracting(d -> d.term() + ": " + d.what()).containsExactly("customer: mdc.sources.params [clientId]");
        assertThat(r.lines("svc").getFirst()).contains("company.yaml now defines 2 of this service's entries: orderId (as order), order_id (as order)");

        VocabularyPrune.prune(svc);
        assertThat(Files.readString(svc.resolve("logast.yaml"))).isEqualTo("""
                extends: [../company.yaml]
                vocabulary:
                  # harvested
                  customer:
                    mdc:
                      key: customerId
                      sources: { params: [clientId] }   # a real difference: stays
                  shelfId:
                    mdc: { key: shelfId }
                logging:
                  keyStyle: camel
                """);
        assertThat(VocabularyPrune.check(svc).covered()).isEmpty();
        PolicyLoader.load(svc, null);   // still a valid policy
    }

    @Test
    void relativeExtendsResolveFromTheProjectWhenTheRootIsACopy() throws IOException {
        Files.writeString(tmp.resolve("company.yaml"), "vocabulary:\n  order:\n    mdc: { key: orderId, aliases: [order_id] }\n");
        Path project = tmp.resolve("svc");
        Path copy = tmp.resolve("scratch/copy");
        for (Path p : List.of(project, copy)) {
            Files.createDirectories(p);
            Files.writeString(p.resolve("logast.yaml"), "extends: [../company.yaml]\n");
        }
        assertThat(PolicyLoader.load(copy, null, List.of(), project).mdcAliases()).containsEntry("order_id", "orderId");
    }

    @Test
    void extendsStartingWithTildeIsTheHomeFolder() throws IOException {
        String home = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tmp.toString());
            Files.createDirectories(tmp.resolve(".logast"));
            Files.writeString(tmp.resolve(".logast/company.yaml"), "vocabulary:\n  order:\n    mdc: { key: orderId, aliases: [order_id] }\n");
            Path svc = tmp.resolve("svc");
            Files.createDirectories(svc);
            Files.writeString(svc.resolve("logast.yaml"), "extends: [~/.logast/company.yaml]\n");
            assertThat(PolicyLoader.load(svc, null).mdcAliases()).containsEntry("order_id", "orderId");
        } finally {
            System.setProperty("user.home", home);
        }
    }
}
