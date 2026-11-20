package dev.logast.export;

import dev.logast.facts.Edge;
import dev.logast.facts.FactSink;
import dev.logast.facts.FactWriter;
import dev.logast.facts.Header;
import dev.logast.facts.Node;
import dev.logast.kb.KnowledgeBase;
import dev.logast.kb.Workflows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** #167: GitHub Actions workflows: reusable ones, their callers' refs, runner labels, actions, scripts. */
class WorkflowExtractorTest {
    @TempDir
    Path tmp;

    private Path repo(String name, String workflow, String file) throws IOException {
        Path root = tmp.resolve(name);
        Files.createDirectories(root.resolve(".github/workflows"));
        Files.writeString(root.resolve(".github/workflows/" + file), workflow);
        return root;
    }

    private static final String CALLER = """
            name: build
            on:
              push:
                branches: [main]
            jobs:
              build:
                uses: org/ci/.github/workflows/gradle.yml@REF
                secrets: inherit
              deploy:
                runs-on: [self-hosted, linux, deploy]
                steps:
                  - uses: actions/checkout@v4
                  - run: |
                      chmod +x ./scripts/deploy.sh
                      ./scripts/deploy.sh prod
                  - run: bash ci/missing.sh
            """;

    private static final String REUSABLE = """
            name: gradle
            on:
              workflow_call:
                inputs:
                  java-version:
                    type: string
                secrets:
                  NEXUS_TOKEN:
                    required: true
              schedule:
                - cron: "0 3 * * 1"
            jobs:
              build:
                runs-on: ubuntu-latest
                steps:
                  - uses: actions/setup-java@v4
            """;

    @Test
    void workflowsTheirCallsActionsLabelsAndScripts() throws IOException {
        Path orders = repo("orders", CALLER.replace("REF", "v3"), "build.yml");
        Files.createDirectories(orders.resolve("scripts"));
        Files.writeString(orders.resolve("scripts/deploy.sh"), "#!/bin/sh\n");
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        new WorkflowExtractor("org/orders").extract(orders, new FactSink() {
            @Override
            public void node(Node n) {
                nodes.add(n);
            }

            @Override
            public void edge(Edge e) {
                edges.add(e);
            }
        });
        assertThat(nodes).singleElement().satisfies(n -> {
            assertThat(n.id()).isEqualTo("workflow:org/orders/.github/workflows/build.yml");
            assertThat(n.attrs()).containsEntry("triggers", List.of("push")).containsEntry("runsOn", List.of("self-hosted", "linux", "deploy"));
        });
        assertThat(edges).extracting(e -> e.kind() + " " + e.to() + " " + e.attrs().getOrDefault("ref", "")).containsExactlyInAnyOrder(
                "calls workflow:org/ci/.github/workflows/gradle.yml v3",
                "uses action:actions/checkout v4",
                "runs script:org/orders/scripts/deploy.sh ");
        assertThat(edges).as("a script the repo does not have is no fact").noneMatch(e -> e.to().contains("missing"));

        Path ci = repo("ci", REUSABLE, "gradle.yml");
        List<Node> ciNodes = new ArrayList<>();
        new WorkflowExtractor("org/ci").extract(ci, new FactSink() {
            @Override
            public void node(Node n) {
                ciNodes.add(n);
            }

            @Override
            public void edge(Edge e) {
            }
        });
        assertThat(ciNodes.getFirst().attrs()).containsEntry("reusable", true).containsEntry("inputs", List.of("java-version"))
                .containsEntry("secrets", List.of("NEXUS_TOKEN")).containsEntry("schedule", "0 3 * * 1").containsEntry("scheduleFormat", "unix");
    }

    @Test
    void whoIsOnWhichRef() throws IOException {
        Path kb = Files.createDirectories(tmp.resolve("kb"));
        int i = 0;
        for (String[] r : new String[][]{{"orders", "v3"}, {"billing", "v2"}, {"shipping", "v3"}}) {
            Path root = repo(r[0], CALLER.replace("REF", r[1]), "build.yml");
            StringWriter w = new StringWriter();
            try (FactWriter fw = new FactWriter(w, Header.of("github-actions", null, r[0], null))) {
                new WorkflowExtractor("org/" + r[0]).extract(root, fw);
            }
            Files.writeString(kb.resolve((i++) + ".jsonl"), w.toString());
        }
        String refs = Workflows.refs(KnowledgeBase.load(List.of(kb)), "gradle");
        assertThat(refs).startsWith("workflow:org/ci/.github/workflows/gradle.yml  (2 refs in use)")
                .contains("@v2 (1):\n  - workflow:org/billing/.github/workflows/build.yml", "@v3 (2):");
    }
}
