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
        TerraformExtractor tx = new TerraformExtractor();
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

    @Test
    void aSecretsConsumersFromTerraformToTheCode() throws IOException {
        Path root = repo();
        Path kb = Files.createDirectories(tmp.resolve("kb"));
        StringWriter tf = new StringWriter();
        try (FactWriter w = new FactWriter(tf, Header.of("terraform", null, "infra", null))) {
            new TerraformExtractor().extract(root, w);
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
