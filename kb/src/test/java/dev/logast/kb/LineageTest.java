package dev.logast.kb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** #164, #165: links across services, lineage, and the risks it shows. */
class LineageTest {
    @TempDir
    Path tmp;

    private static String node(String id, String kind, String attrs) {
        return "{\"node\":{\"id\":\"" + id + "\",\"kind\":\"" + kind + "\"" + (attrs == null ? "" : ",\"attrs\":" + attrs)
                + ",\"evidence\":{\"file\":\"" + id.replaceAll("[^a-zA-Z0-9]", "_") + "\"}}}\n";
    }

    private static String edge(String from, String kind, String to) {
        return "{\"edge\":{\"from\":\"" + from + "\",\"kind\":\"" + kind + "\",\"to\":\"" + to
                + "\",\"provenance\":\"extracted\",\"evidence\":{\"file\":\"src/" + kind + ".java\",\"line\":1}}}\n";
    }

    private KnowledgeBase kb() throws IOException {
        String f = "{\"header\":{\"schema\":\"kb-facts/0\",\"extractor\":\"java\",\"repo\":\"shop\"}}\n"
                + node("service:orders", "service", null) + node("service:billing", "service", null)
                + node("service:shipping", "service", null) + node("service:ledger", "service", null)
                // orders: an HTTP call by host, one by path only, one two services have; a Kafka message
                + node("endpoint:orders:POST /orders", "endpoint", "{\"method\":\"POST\",\"path\":\"/orders\"}")
                + node("call:orders:http POST http://billing/invoices", "call", "{\"transport\":\"http\",\"method\":\"POST\",\"target\":\"/invoices\",\"base\":\"http://billing:8080\"}")
                + node("call:orders:http GET /receipts/{id}", "call", "{\"transport\":\"http\",\"method\":\"GET\",\"target\":\"/receipts/{id}\"}")
                + node("call:orders:http GET http://localhost:8080/receipts/{id}", "call", "{\"transport\":\"http\",\"method\":\"GET\",\"target\":\"/receipts/{id}\",\"base\":\"http://localhost:8080\"}")
                + node("call:orders:http GET /status/{?}", "call", "{\"transport\":\"http\",\"method\":\"GET\",\"target\":\"/status/{?}\"}")
                + node("call:orders:http GET http://archive/invoices", "call", "{\"transport\":\"http\",\"method\":\"POST\",\"target\":\"/invoices\",\"base\":\"http://archive\"}")
                + node("call:orders:kafka orders.placed", "call", "{\"transport\":\"kafka\",\"target\":\"orders.placed\"}")
                + edge("endpoint:orders:POST /orders", "calls", "call:orders:http POST http://billing/invoices")
                + edge("endpoint:orders:POST /orders", "calls", "call:orders:kafka orders.placed")
                + edge("call:orders:kafka orders.placed", "publishes", "topic:orders.placed")
                // billing
                + node("endpoint:billing:POST /invoices", "endpoint", "{\"method\":\"POST\",\"path\":\"/invoices\"}")
                + node("endpoint:billing:GET /receipts/{receiptId}", "endpoint", "{\"method\":\"GET\",\"path\":\"/receipts/{receiptId}\"}")
                + node("endpoint:billing:GET /status/{id}", "endpoint", "{\"method\":\"GET\",\"path\":\"/status/{id}\"}")
                + edge("endpoint:billing:POST /invoices", "writes", "table:billing.invoice")
                + edge("listener:billing:kafka orders.placed", "consumes", "topic:orders.placed")
                + edge("listener:billing:kafka orders.placed", "writes", "table:audit_event")
                // shipping
                + node("endpoint:shipping:GET /status/{id}", "endpoint", "{\"method\":\"GET\",\"path\":\"/status/{id}\"}")
                + node("job:shipping:Report.run", "job", "{\"cron\":\"0 0 4 * * *\"}")
                + edge("job:shipping:Report.run", "reads", "table:public.audit_event")
                + node("table:public.audit_event", "table", null)
                // a timing-only handoff: an orchestrated job of billing writes, a @Scheduled job of shipping reads
                + node("job:billing:invoice-export", "job", "{\"schedule\":\"0 2 * * *\",\"scheduleFormat\":\"unix\"}")
                + edge("job:billing:invoice-export", "runs", "profile:billing:export")
                + edge("bean:billing:ExportJob", "active-with", "profile:billing:export")
                + edge("listener:billing:event onReady", "runs", "bean:billing:ExportJob")
                + edge("listener:billing:event onReady", "writes", "table:billing.export_batch")
                + node("job:shipping:Import.run", "job", "{\"cron\":\"0 0 3 * * *\"}")
                + edge("job:shipping:Import.run", "reads", "table:billing.export_batch")
                // a guarded one: a pipeline step runs ledger's job after billing's
                + node("job:billing:settle", "job", "{\"schedule\":\"0 1 * * *\",\"scheduleFormat\":\"unix\"}")
                + edge("job:billing:settle", "runs", "profile:billing:settle")
                + edge("bean:billing:SettleJob", "active-with", "profile:billing:settle")
                + edge("listener:billing:settleReady", "runs", "bean:billing:SettleJob")
                + edge("listener:billing:settleReady", "writes", "table:billing.settlement")
                + node("job:ledger:post", "job", "{\"schedule\":\"30 1 * * *\",\"scheduleFormat\":\"unix\"}")
                + edge("job:ledger:post", "runs", "profile:ledger:post")
                + edge("bean:ledger:PostJob", "active-with", "profile:ledger:post")
                + edge("listener:ledger:ready", "runs", "bean:ledger:PostJob")
                + edge("listener:ledger:ready", "reads", "table:billing.settlement")
                + edge("job:billing:settle", "after", "job:ledger:post")
                // a file handoff: billing's job writes an SFTP folder shipping's job reads (#172)
                + node("job:billing:Outbound.push", "job", "{\"cron\":\"0 30 2 * * *\"}")
                + edge("job:billing:Outbound.push", "writes", "file-location:sftp:/outbound/invoices/{?}.csv")
                + node("job:shipping:Inbound.pull", "job", "{\"cron\":\"0 0 3 * * *\"}")
                + edge("job:shipping:Inbound.pull", "reads", "file-location:sftp:/outbound/invoices/{?}.csv")
                // ledger writes its own schema, and billing's invoice table too
                + edge("listener:ledger:ready", "writes", "table:ledger.entry")
                + edge("endpoint:ledger:POST /corrections", "writes", "table:billing.invoice");
        Files.writeString(tmp.resolve("shop.jsonl"), f);
        KnowledgeBase kb = KnowledgeBase.load(List.of(tmp));
        assertThat(kb.problems()).isEmpty();
        return kb;
    }

    @Test
    void callsReachEndpointsByHostOrPath() throws IOException {
        KnowledgeBase kb = kb();
        assertThat(kb.neighbors("call:orders:http POST http://billing/invoices", "out", List.of("reaches"), 10))
                .contains("-reaches-> endpoint:billing:POST /invoices [inferred via http:host]");
        assertThat(kb.neighbors("call:orders:http GET /receipts/{id}", "out", List.of("reaches"), 10))
                .contains("-reaches-> endpoint:billing:GET /receipts/{receiptId} [inferred via http:path]");
        assertThat(kb.neighbors("call:orders:http GET /status/{?}", "out", List.of("reaches"), 10))
                .contains("endpoint:billing:GET /status/{id} [inferred via http:path-ambiguous]", "endpoint:shipping:GET /status/{id} [inferred via http:path-ambiguous]");
        assertThat(kb.neighbors("call:orders:http GET http://archive/invoices", "out", List.of("reaches"), 10))
                .as("a host that names no service of the facts: no link").doesNotContain("reaches");
        assertThat(kb.neighbors("table:audit_event", "out", List.of("same-as"), 10)).contains("-same-as-> table:public.audit_event");
        assertThat(Lineage.host("http://shipping.finance.svc.cluster.local:8080")).isEqualTo("shipping");
        assertThat(Lineage.host("http://localhost:8080")).as("#178: a local default names no target").isNull();
        assertThat(Lineage.host("http://127.0.0.1:8080/api")).isNull();
        assertThat(kb.neighbors("call:orders:http GET http://localhost:8080/receipts/{id}", "out", List.of("reaches"), 10))
                .contains("-reaches-> endpoint:billing:GET /receipts/{receiptId} [inferred via http:path]");
        assertThat(Lineage.pathMatches("/a/{?}/b", "/a/{id}/b")).isTrue();
        assertThat(Lineage.pathMatches("/a/x", "/a/y")).isFalse();
    }

    @Test
    void lineageFollowsTheChainAcrossServices() throws IOException {
        KnowledgeBase kb = kb();
        String down = Lineage.lineage(kb, "endpoint:orders:POST /orders", true, 8, 200);
        assertThat(down).contains("- calls → call:orders:kafka orders.placed", "- publishes → topic:orders.placed",
                "- ← consumes by listener:billing:kafka orders.placed", "- writes → table:audit_event",
                "- same-as → table:public.audit_event", "- ← reads by job:shipping:Report.run",
                "- reaches → endpoint:billing:POST /invoices [inferred http:host]", "- writes → table:billing.invoice");
        assertThat(down.indexOf("topic:orders.placed")).as("a tree, depth-first").isLessThan(down.indexOf("listener:billing:kafka"));
        String up = Lineage.lineage(kb, "table:public.audit_event", false, 8, 200);
        assertThat(up).contains("table:audit_event", "listener:billing:kafka orders.placed", "topic:orders.placed",
                "call:orders:kafka orders.placed", "endpoint:orders:POST /orders");
        assertThat(Lineage.lineage(kb, "job:billing:invoice-export", true, 8, 200)).as("an orchestrated job down to the table its code writes")
                .contains("profile:billing:export", "bean:billing:ExportJob", "listener:billing:event onReady", "table:billing.export_batch",
                        "job:shipping:Import.run");
    }

    @Test
    void risksAcrossServices() throws IOException {
        String r = Lineage.risks(kb(), null);
        assertThat(r).contains("- table:billing.export_batch: listener:billing:event onReady writes (job:billing:invoice-export 0 2 * * *), "
                + "job:shipping:Import.run reads (0 0 3 * * *) — NO pipeline step between them: the reader relies on timing");
        assertThat(r).contains("table:billing.settlement: listener:billing:settleReady writes", "— a pipeline step runs the reader after the writer");
        assertThat(r).contains("- table:billing.invoice: written by billing, ledger");
        assertThat(r).as("through a file too").contains("- file-location:sftp:/outbound/invoices/{?}.csv: job:billing:Outbound.push writes (0 0 2 * * *".replace("0 0 2", "0 30 2"),
                "job:shipping:Inbound.pull reads (0 0 3 * * *) — NO pipeline step");
        assertThat(r).contains("- listener:ledger:ready reads table:billing.settlement (ledger writes ledger)");
        assertThat(r).as("audit_event: writer and reader are not both scheduled jobs").doesNotContain("audit_event:");
    }
}
