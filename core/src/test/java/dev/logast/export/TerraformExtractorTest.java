package dev.logast.export;

import dev.logast.facts.Edge;
import dev.logast.facts.FactSink;
import dev.logast.facts.FactWriter;
import dev.logast.facts.Header;
import dev.logast.facts.Node;
import dev.logast.kb.KnowledgeBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/** #166: a Terragrunt repo's resources and Key Vault secrets per environment, without the CLIs. */
class TerraformExtractorTest {
    @TempDir
    Path tmp;

    private Path repo() throws IOException {
        Path root = tmp.resolve("infra");
        Files.createDirectories(root.resolve("terragrunt"));
        Files.writeString(root.resolve("terragrunt/root.hcl"), """
                inputs = {
                  location = "westeurope"
                }
                """);
        for (String env : List.of("dev", "prod")) {
            Path unit = Files.createDirectories(root.resolve("terragrunt/sub-shop/weu/" + env + "/orders"));
            Files.writeString(unit.resolve("terragrunt.hcl"), """
                    include "root" {
                      path = find_in_parent_folders("root.hcl")
                    }

                    terraform {
                      source = "${get_repo_root()}/terraform//orders"
                    }

                    inputs = {
                      env = "ENV"
                    }
                    """.replace("ENV", env));
        }
        Path module = Files.createDirectories(root.resolve("terraform/orders/modules/storage"));
        Files.writeString(module.getParent().getParent().resolve("main.tf"), """
                variable "env" {
                  type = string
                }

                variable "app" {
                  default = "orders"
                }

                locals {
                  vault_name = "kv-${var.app}-${var.env}"
                }

                resource "azurerm_key_vault" "main" {
                  name     = local.vault_name
                  location = var.location
                }

                resource "azurerm_key_vault_secret" "db" {
                  name            = "db-password"
                  value           = random_password.db.result
                  key_vault_id    = azurerm_key_vault.main.id
                  content_type    = "password"
                  expiration_date = "2026-12-01T00:00:00Z"
                  tags = {
                    owner = "team-orders"
                  }
                }

                resource "azurerm_postgresql_flexible_server" "pg" {
                  name     = "pg-${var.app}-${var.env}"
                  location = var.location
                  sku_name = lookup(var.skus, var.env, "B_Standard_B1ms")
                }

                module "storage" {
                  source = "./modules/storage"
                  account = "st${var.app}${var.env}"
                }
                """);
        Files.writeString(module.resolve("main.tf"), """
                variable "account" {}

                resource "azurerm_storage_account" "this" {
                  name         = var.account
                  account_tier = "Standard"
                }
                """);
        return root;
    }

    @Test
    void unitsEvaluateTheirModulePerEnvironment() throws IOException {
        Path root = repo();
        assertThat(TerraformExtractor.applies(root)).isTrue();
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        TerraformExtractor tx = new TerraformExtractor(false);
        tx.extract(root, new FactSink() {
            @Override
            public void node(Node n) {
                nodes.add(n);
            }

            @Override
            public void edge(Edge e) {
                edges.add(e);
            }
        });
        assertThat(tx.problems()).isEmpty();
        Node secret = nodes.stream().filter(n -> n.id().equals("secret:kv-orders-prod/db-password") && "prod".equals(n.env())).findFirst().orElseThrow();
        assertThat(secret.attrs()).containsEntry("content_type", "password").containsEntry("expiration_date", "2026-12-01T00:00:00Z")
                .containsEntry("tags", Map.of("owner", "team-orders")).containsEntry("vault", "kv-orders-prod");
        assertThat(secret.evidence().file()).isEqualTo("terraform/orders/main.tf");
        assertThat(secret.evidence().line()).isEqualTo(18);
        assertThat(nodes).extracting(n -> n.id() + " [" + n.env() + "]").contains(
                "secret:kv-orders-dev/db-password [dev]",
                "resource:azurerm_key_vault:kv-orders-prod [prod]",
                "resource:azurerm_postgresql_flexible_server:pg-orders-dev [dev]",
                "resource:azurerm_storage_account:stordersprod [prod]",
                "tf-unit:terragrunt/sub-shop/weu/prod/orders [prod]");
        Node pg = nodes.stream().filter(n -> n.id().equals("resource:azurerm_postgresql_flexible_server:pg-orders-prod")).findFirst().orElseThrow();
        assertThat(pg.attrs()).as("the root file's input").containsEntry("location", "westeurope")
                .as("a function evaluated at plan time stays unknown").doesNotContainKey("sku_name");
        assertThat(edges).filteredOn(e -> Objects.equals(e.env(), "prod")).extracting(e -> e.from() + " -" + e.kind() + "-> " + e.to())
                .contains("resource:azurerm_key_vault:kv-orders-prod -has-> secret:kv-orders-prod/db-password",
                        "tf-unit:terragrunt/sub-shop/weu/prod/orders -defines-> resource:azurerm_storage_account:stordersprod");
    }

    /** #175: with terragrunt installed, its own functions are evaluated: names built from them come out right. */
    @Test
    void terragruntRenderEvaluatesItsFunctions() throws IOException, InterruptedException {
        org.junit.jupiter.api.Assumptions.assumeTrue(TerraformExtractor.terragruntAvailable(), "needs terragrunt on the PATH");
        Path root = tmp.resolve("infra2");
        Path unit = Files.createDirectories(root.resolve("terragrunt/sub/weu/prod/orders"));
        Files.createDirectories(root.resolve("terraform/orders"));
        new ProcessBuilder("git", "init", "-q", root.toString()).start().waitFor();   // get_repo_root()
        Files.writeString(root.resolve("terragrunt/root.hcl"), "inputs = {\n  owner = get_env(\"LOGAST_TEST_UNSET_VAR\", \"team-orders\")\n}\n");
        Files.writeString(unit.resolve("terragrunt.hcl"), """
                include "root" {
                  path = find_in_parent_folders("root.hcl")
                }
                terraform {
                  source = "${get_repo_root()}/terraform//orders"
                }
                inputs = {
                  env = "prod"
                  app = basename(get_terragrunt_dir())
                }
                """);
        Files.writeString(root.resolve("terraform/orders/main.tf"), """
                variable "env" {}
                variable "app" {}
                variable "owner" {}
                resource "azurerm_key_vault" "main" {
                  name = "kv-${var.app}-${var.env}"
                  tags = {
                    owner = var.owner
                  }
                }
                """);
        List<Node> nodes = new ArrayList<>();
        TerraformExtractor tx = new TerraformExtractor(true);
        tx.extract(root, new FactSink() {
            @Override
            public void node(Node n) {
                nodes.add(n);
            }

            @Override
            public void edge(Edge e) {
            }
        });
        assertThat(tx.problems()).isEmpty();
        assertThat(nodes).extracting(Node::id).contains("resource:azurerm_key_vault:kv-orders-prod");
        assertThat(nodes).filteredOn(n -> n.kind().equals("tf-unit")).singleElement()
                .satisfies(n -> assertThat(n.attrs()).containsEntry("inputsFrom", "terragrunt"));
        assertThat(nodes).filteredOn(n -> n.id().startsWith("resource:")).singleElement()
                .satisfies(n -> assertThat(n.attrs()).containsEntry("tags", Map.of("owner", "team-orders")));
    }

    /** #175: an older terragrunt has no render, only render-json writing to a file: its inputs still count. */
    @Test
    void anOlderTerragruntRendersWithRenderJson() throws IOException {
        Path root = repo();
        Path fake = tmp.resolve("old-terragrunt");
        Files.writeString(fake, """
                #!/bin/sh
                # like terragrunt 0.6x: render goes to terraform and fails; render-json writes the resolved config
                if [ "$1" = render ]; then echo 'Terraform has no command named "render"' >&2; exit 1; fi
                if [ "$1" = render-json ]; then
                  printf '{"inputs":{"env":"prod","app":"billing","location":"northeurope"},"terraform":{"source":"x/terraform//orders"}}' > "$3"
                  exit 0
                fi
                exit 1
                """);
        fake.toFile().setExecutable(true);
        List<Node> nodes = new ArrayList<>();
        TerraformExtractor tx = new TerraformExtractor(true, fake.toString());
        tx.extract(root, new FactSink() {
            @Override
            public void node(Node n) {
                nodes.add(n);
            }

            @Override
            public void edge(Edge e) {
            }
        });
        assertThat(tx.problems()).isEmpty();
        assertThat(nodes).extracting(Node::id).as("the rendered inputs, not the files'").contains("resource:azurerm_key_vault:kv-billing-prod");
        assertThat(nodes).filteredOn(n -> n.kind().equals("tf-unit")).allSatisfy(n -> assertThat(n.attrs()).containsEntry("inputsFrom", "terragrunt"));
    }

    /** The classic layout: a root terragrunt.hcl (remote state, shared arguments) the folders below include, relative sources. */
    @Test
    void theClassicLayoutWithARootTerragruntFile() throws IOException {
        Path root = tmp.resolve("live-repo");
        Files.createDirectories(root.resolve("modules/orders"));
        Files.writeString(root.resolve("terragrunt.hcl"), """
                remote_state {
                  backend = "azurerm"
                  config = {
                    key = "${path_relative_to_include()}/terraform.tfstate"
                  }
                }
                terraform {
                  extra_arguments "common" {
                    commands = ["plan", "apply"]
                  }
                }
                inputs = {
                  location = "westeurope"
                }
                """);
        for (String env : List.of("rtl", "prod")) {
            Path unit = Files.createDirectories(root.resolve("live/" + env + "/orders"));
            Files.writeString(unit.resolve("terragrunt.hcl"), """
                    include {
                      path = find_in_parent_folders()
                    }
                    terraform {
                      source = "../../../modules/orders"
                    }
                    inputs = {
                      env = "ENV"
                    }
                    """.replace("ENV", env));
        }
        Path remote = Files.createDirectories(root.resolve("live/prod/network"));
        Files.writeString(remote.resolve("terragrunt.hcl"), """
                include {
                  path = find_in_parent_folders()
                }
                terraform {
                  source = "git::https://git.example.com/infra-modules.git//network?ref=v1.2.0"
                }
                """);
        Files.writeString(root.resolve("modules/orders/main.tf"), """
                variable "env" {}
                variable "location" {}
                resource "azurerm_resource_group" "main" {
                  name     = "rg-orders-${var.env}"
                  location = var.location
                }
                """);
        assertThat(TerraformExtractor.units(root)).as("the root file is no unit").extracting(p -> root.relativize(p).toString())
                .containsExactly("live/prod/network/terragrunt.hcl", "live/prod/orders/terragrunt.hcl", "live/rtl/orders/terragrunt.hcl");
        List<Node> nodes = new ArrayList<>();
        TerraformExtractor tx = new TerraformExtractor(false);
        tx.extract(root, new FactSink() {
            @Override
            public void node(Node n) {
                nodes.add(n);
            }

            @Override
            public void edge(Edge e) {
            }
        });
        assertThat(nodes).extracting(n -> n.id() + " [" + n.env() + "]").contains(
                "resource:azurerm_resource_group:rg-orders-rtl [rtl]", "resource:azurerm_resource_group:rg-orders-prod [prod]");
        assertThat(nodes).filteredOn(n -> n.id().equals("resource:azurerm_resource_group:rg-orders-prod")).singleElement()
                .satisfies(n -> assertThat(n.attrs()).as("from the root file's inputs").containsEntry("location", "westeurope"));
        assertThat(tx.problems()).singleElement().asString().contains("live/prod/network", "the module is in another repo");
    }

    @Test
    void aSecretsConsumersFromTerraformToTheCode() throws IOException {
        Path root = repo();
        Path kb = Files.createDirectories(tmp.resolve("kb"));
        StringWriter tf = new StringWriter();
        try (FactWriter w = new FactWriter(tf, Header.of("terraform", null, "infra", null))) {
            new TerraformExtractor(false).extract(root, w);
        }
        Files.writeString(kb.resolve("infra.jsonl"), tf.toString());
        Files.writeString(kb.resolve("deploy.jsonl"), """
                {"header":{"schema":"kb-facts/0","extractor":"helm","repo":"deploy"}}
                {"node":{"id":"deployment:orders","kind":"deployment","env":"prod","evidence":{"file":"charts/orders/templates/deployment.yaml"}}}
                {"edge":{"from":"deployment:orders","kind":"uses-secret","to":"secret:kv-orders-prod/db-password","env":"prod","provenance":"extracted","evidence":{"file":"charts/orders/templates/deployment.yaml"}}}
                {"node":{"id":"service:orders","kind":"service","evidence":{"file":"build.gradle.kts"}}}
                """);
        KnowledgeBase k = KnowledgeBase.load(List.of(kb));
        String all = dev.logast.kb.Secrets.list(k, null, "prod", null, java.time.LocalDate.parse("2026-11-20"));
        assertThat(all).contains("secret:kv-orders-prod/db-password [prod], expires 2026-12-01T00:00:00Z, password",
                "used by deployment:orders [prod] (code: service:orders)");
        assertThat(dev.logast.kb.Secrets.list(k, null, "dev", 30, java.time.LocalDate.parse("2026-11-20")))
                .contains("secret:kv-orders-dev/db-password [dev]", "no workload mounts or reads it");
        assertThat(dev.logast.kb.Secrets.list(k, null, null, 5, java.time.LocalDate.parse("2026-11-20"))).startsWith("No secret expires within 5 days");
    }
}
