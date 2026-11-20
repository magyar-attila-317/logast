package dev.logast.export;

import dev.logast.Fixtures;
import dev.logast.facts.FactReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** #182: a whole repo's facts, whatever its layout, one file per root. */
class RepoExportTest {
    @TempDir
    Path tmp;

    private static void java(Path dir, String pkg, String cls, String path) throws IOException {
        Path f = dir.resolve("src/main/java/" + pkg.replace('.', '/') + "/" + cls + ".java");
        Files.createDirectories(f.getParent());
        Files.writeString(f, """
                package %s;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                class %s {
                    @GetMapping("%s")
                    String get() {
                        return "ok";
                    }
                }
                """.formatted(pkg, cls, path));
    }

    private Path monorepo() throws IOException {
        Path repo = tmp.resolve("platform");
        Path orders = Files.createDirectories(repo.resolve("apps/orders"));
        Files.writeString(orders.resolve("settings.gradle.kts"), "rootProject.name = \"orders\"\ninclude(\"api\")\n");
        Files.writeString(orders.resolve("build.gradle.kts"), "plugins { java }\n");
        java(orders, "com.example.orders", "OrderController", "/orders");
        Files.createDirectories(orders.resolve("api"));
        Files.writeString(orders.resolve("api/build.gradle.kts"), "plugins { `java-library` }\n");
        java(orders.resolve("api"), "com.example.orders.api", "Ping", "/ping");
        Path billing = Files.createDirectories(repo.resolve("apps/billing"));
        Files.writeString(billing.resolve("build.gradle.kts"), "plugins { java }\n");
        java(billing, "com.example.billing", "InvoiceController", "/invoices");
        // not roots: a dependency folder and a git worktree inside the repo
        Path nm = Files.createDirectories(repo.resolve("web/node_modules/some-lib"));
        Files.writeString(nm.resolve("build.gradle"), "");
        java(nm, "x", "X", "/x");
        Path wt = Files.createDirectories(repo.resolve("worktrees2/feature"));
        Files.writeString(wt.resolve(".git"), "gitdir: ../../.git/worktrees/feature\n");
        Files.writeString(wt.resolve("build.gradle.kts"), "");
        java(wt, "y", "Y", "/y");
        Path unit = Files.createDirectories(repo.resolve("infra/terragrunt/sub/weu/prod/orders"));
        Files.writeString(unit.resolve("terragrunt.hcl"), "terraform {\n  source = \"../../../../../terraform/orders\"\n}\ninputs = {\n  env = \"prod\"\n}\n");
        Path mod = Files.createDirectories(repo.resolve("infra/terraform/orders"));
        Files.writeString(mod.resolve("main.tf"), "variable \"env\" {}\nresource \"azurerm_resource_group\" \"main\" {\n  name = \"rg-orders-${var.env}\"\n}\n");
        java(repo.resolve("tools/loader"), "com.example.loader", "LoaderController", "/load");   // sources without a build file
        Path wf = Files.createDirectories(repo.resolve(".github/workflows"));
        Files.writeString(wf.resolve("build.yml"), "on: [push]\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n      - uses: actions/checkout@v4\n");
        return repo;
    }

    @Test
    void everyRootIsFoundWithoutConfiguration() throws IOException {
        Path repo = monorepo();
        assertThat(RepoExport.roots(repo)).extracting(r -> repo.relativize(r.dir()).toString() + " " + r.kind()).containsExactly(
                "apps/billing java", "apps/orders java", "tools/loader java", " terraform", " workflows");
    }

    @Test
    void oneFilePerRootWithItsRootInEveryFact() throws IOException {
        Path repo = monorepo();
        Path out = tmp.resolve("kb/platform");
        Files.createDirectories(out);
        Files.writeString(out.resolve("stale.jsonl"), "{}\n");
        RepoExport.Result r = RepoExport.export(repo, "platform", "test",
                new RepoExport.Options(root -> Fixtures.classpath(), null, List.of(), List.of()), out);
        assertThat(r.problems()).isEmpty();
        assertThat(r.files()).extracting(f -> f.getFileName().toString()).containsExactly(
                "apps_billing.jsonl", "apps_orders.jsonl", "tools_loader.jsonl", "repo.terraform.jsonl", "repo.workflows.jsonl");
        assertThat(out.resolve("stale.jsonl")).as("an earlier export's files are replaced").doesNotExist();

        String orders = Files.readString(out.resolve("apps_orders.jsonl"));
        FactReader.Result facts = FactReader.read(new StringReader(orders));
        assertThat(facts.problems()).isEmpty();
        assertThat(facts.nodes()).filteredOn(n -> n.kind().equals("endpoint")).extracting(n -> n.id() + " " + n.evidence().root() + "/" + n.evidence().file())
                .as("a build's modules are part of it; every fact names its root").contains(
                        "endpoint:orders:GET /orders apps/orders/src/main/java/com/example/orders/OrderController.java",
                        "endpoint:orders:GET /ping apps/orders/api/src/main/java/com/example/orders/api/Ping.java");
        assertThat(Files.readString(out.resolve("repo.terraform.jsonl"))).contains("resource:azurerm_resource_group:rg-orders-prod");
        assertThat(Files.readString(out.resolve("repo.workflows.jsonl"))).contains("action:actions/checkout");
        assertThat(Files.readString(out.resolve("apps_billing.jsonl"))).contains("GET /invoices").doesNotContain("workflow:");
    }
}
