package dev.logast.cli;

import dev.logast.facts.Edge;
import dev.logast.facts.Evidence;
import dev.logast.facts.FactWriter;
import dev.logast.facts.Header;
import dev.logast.facts.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The line under a fleet member's line: what its repo holds, and once exported, what its facts count. */
class MemberSummaryTest {
    @TempDir
    Path tmp;

    @Test
    void whatTheRepoHoldsThenWhatItsFactsCount() throws IOException {
        Path repo = tmp.resolve("billing");
        Files.createDirectories(repo.resolve("src/main/java/com/example"));
        Files.writeString(repo.resolve("settings.gradle.kts"), "rootProject.name = \"billing\"\n");
        Path migrations = Files.createDirectories(repo.resolve("src/main/resources/db/migration"));
        Files.writeString(migrations.resolve("V1__invoice.sql"), "create table invoice (id bigint);\n");
        Files.createDirectories(repo.resolve(".github/workflows"));
        Files.writeString(repo.resolve(".github/workflows/build.yml"), "on: push\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n      - run: ./gradlew build\n");

        assertThat(MemberSummary.of(repo, null)).as("scanned, not exported: the kinds of code only")
                .isEqualTo("Java · workflows · Flyway migrations, not run (--kb --flyway runs them)");

        Path facts = Files.createDirectories(tmp.resolve("kb/billing"));
        Evidence at = Evidence.at("src/main/java/com/example/Billing.java", 1);
        try (Writer w = Files.newBufferedWriter(facts.resolve("repo.jsonl")); FactWriter out = new FactWriter(w, Header.of("java+flyway", "test", "billing", "abc"))) {
            out.node(new Node("endpoint:billing:GET /invoices", "endpoint", null, Map.of(), at));
            out.node(new Node("endpoint:billing:POST /invoices", "endpoint", null, Map.of(), at));
            out.node(new Node("job:billing:InvoiceRunJob.run", "job", null, Map.of(), at));
            out.node(new Node("table:billing.invoice", "table", "dev", Map.of(), at));
            out.node(new Node("table:billing.invoice", "table", "prod", Map.of(), at));   // one table, in two environments
            out.node(new Node("workflow:billing:build", "workflow", null, Map.of(), at));
            out.edge(Edge.extracted("job:billing:InvoiceRunJob.run", "writes", "table:billing.invoice", null, at));
            out.edge(Edge.extracted("job:billing:InvoiceRunJob.run", "reads", "table:orders.purchase_order", null, at));
            out.edge(Edge.extracted("endpoint:billing:POST /invoices", "publishes", "topic:invoice-events", null, at));
        }
        assertThat(MemberSummary.of(repo, facts)).as("exported: what its facts count, and that Flyway ran")
                .isEqualTo("Java: 2 endpoints, 1 job, 2 tables, 1 topic/queue · workflows: 1 workflow · Flyway ran: 1 table in 2 environments");
    }

    @Test
    void aRepoWithNothingLogastReads() throws IOException {
        Path repo = Files.createDirectories(tmp.resolve("notes"));
        Files.writeString(repo.resolve("README.md"), "# notes\n");
        assertThat(MemberSummary.of(repo, null)).isEqualTo("nothing logast reads (no Java build, Helm, Terraform or workflows)");
    }
}
