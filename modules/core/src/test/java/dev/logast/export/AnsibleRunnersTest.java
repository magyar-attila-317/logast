package dev.logast.export;

import dev.logast.facts.FactWriter;
import dev.logast.facts.Header;
import dev.logast.kb.KnowledgeBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** #173: self-hosted runners an Ansible inventory sets up, and the workflows that run on them. */
class AnsibleRunnersTest {
    @TempDir
    Path tmp;

    @Test
    void runnersFromInventoriesAndTheWorkflowsTheyRun() throws IOException {
        Path ci = tmp.resolve("ci");
        Files.createDirectories(ci.resolve("ansible/inventory/group_vars"));
        Files.createDirectories(ci.resolve("ansible/inventory/host_vars"));
        Files.writeString(ci.resolve("ansible/inventory/hosts.ini"), """
                [build_runners]
                build-01.example.internal
                build-02.example.internal runner_count=4

                [deploy_runners]
                deploy-01.example.internal

                [monitoring]
                grafana-01.example.internal
                """);
        Files.writeString(ci.resolve("ansible/inventory/group_vars/build_runners.yml"), """
                runner_labels: [build, java]
                runner_count: 2
                """);
        Files.writeString(ci.resolve("ansible/inventory/host_vars/deploy-01.example.internal.yml"), """
                github_runner_labels: "deploy,prod"
                """);
        Files.writeString(ci.resolve("ansible/inventory/gpu.yml"), """
                all:
                  children:
                    gpu:
                      hosts:
                        gpu-01.example.internal:
                          runner_labels: [gpu]
                """);
        StringWriter facts = new StringWriter();
        try (FactWriter w = new FactWriter(facts, Header.of("ansible", null, "ci", null))) {
            AnsibleRunners.extract(ci, w);
        }
        String f = facts.toString();
        assertThat(f).contains("\"id\":\"runner-host:build-01.example.internal\"", "\"labels\":[\"build\",\"java\"]",
                "\"id\":\"runner-host:deploy-01.example.internal\"", "\"labels\":[\"deploy\",\"prod\"]",
                "\"id\":\"runner-host:gpu-01.example.internal\"").doesNotContain("grafana");
        assertThat(f).as("a host's own count over its group's").contains("\"count\":\"4\"", "\"count\":\"2\"");

        Path kb = Files.createDirectories(tmp.resolve("kb"));
        Files.writeString(kb.resolve("ci.jsonl"), f);
        Files.writeString(kb.resolve("wf.jsonl"), """
                {"header":{"schema":"kb-facts/0","extractor":"github-actions","repo":"orders"}}
                {"node":{"id":"workflow:org/orders/.github/workflows/build.yml","kind":"workflow","attrs":{"runsOn":["self-hosted","linux","build"]},"evidence":{"file":".github/workflows/build.yml","line":1}}}
                {"node":{"id":"workflow:org/orders/.github/workflows/deploy.yml","kind":"workflow","attrs":{"runsOn":["self-hosted","deploy","prod"]},"evidence":{"file":".github/workflows/deploy.yml","line":1}}}
                {"node":{"id":"workflow:org/orders/.github/workflows/ml.yml","kind":"workflow","attrs":{"runsOn":["self-hosted","tpu"]},"evidence":{"file":".github/workflows/ml.yml","line":1}}}
                {"node":{"id":"workflow:org/orders/.github/workflows/lint.yml","kind":"workflow","attrs":{"runsOn":["ubuntu-latest"]},"evidence":{"file":".github/workflows/lint.yml","line":1}}}
                """);
        String r = dev.logast.kb.Lineage.runners(KnowledgeBase.load(List.of(kb)));
        assertThat(r).startsWith("4 runner host(s):")
                .contains("- build-02.example.internal  groups [build_runners], labels [build, java], 4 runner(s)",
                        "    runs workflow:org/orders/.github/workflows/build.yml",
                        "    runs workflow:org/orders/.github/workflows/deploy.yml",
                        "self-hosted workflows no runner's labels fit:\n- workflow:org/orders/.github/workflows/ml.yml needs [self-hosted, tpu]")
                .doesNotContain("lint.yml");
    }
}
