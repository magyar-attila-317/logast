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

    private static List<Edge> edges(String repo, Path root) {
        List<Edge> edges = new ArrayList<>();
        new WorkflowExtractor(repo).extract(root, new FactSink() {
            @Override
            public void node(Node n) {
            }

            @Override
            public void edge(Edge e) {
                edges.add(e);
            }
        });
        return edges;
    }

    private static String show(Edge e) {
        return e.from() + " -" + e.kind() + "-> " + e.to();
    }

    /**
     * #214: a service's workflow starts a deploy in a GitOps repo (a reusable call with what it passes, a dispatch); the
     * GitOps workflow runs a script that clones the Kubernetes repo, writes the image tag into a values file and pushes.
     */
    @Test
    void aDeployChainAcrossRepos() throws IOException {
        Path orders = repo("orders", """
                name: release
                on: {push: {branches: [main]}}
                jobs:
                  deploy:
                    uses: org/gitops/.github/workflows/deploy-service.yml@main
                    with:
                      service: orders-api
                      tag: ${{ github.sha }}
                    secrets: inherit
                  notify:
                    runs-on: ubuntu-latest
                    steps:
                      - run: gh workflow run deploy-service.yml -R org/gitops -f service=orders-api -f tag=${{ github.sha }}
                      - run: |
                          curl -X POST https://api.github.com/repos/org/gitops/dispatches \\
                            -H "Authorization: Bearer $TOKEN" \\
                            -d '{"event_type":"deploy","client_payload":{"service":"orders-api"}}'
                      - uses: peter-evans/repository-dispatch@v3
                        with:
                          repository: org/gitops
                          event-type: deploy
                          client-payload: '{"service": "orders-api", "token": "s3cr3t"}'
                """, "release.yml");
        List<String> sent = edges("org/orders", orders).stream().map(e -> show(e) + " " + e.attrs()).toList();
        assertThat(sent).contains(
                "workflow:org/orders/.github/workflows/release.yml -calls-> workflow:org/gitops/.github/workflows/deploy-service.yml "
                        + "{job=deploy, ref=main, with={service=orders-api, tag=${{ github.sha }}}}",
                "workflow:org/orders/.github/workflows/release.yml -dispatches-> workflow:org/gitops/.github/workflows/deploy-service.yml "
                        + "{inputs={service=orders-api, tag=${{ github.sha }}}, job=notify, via=gh workflow run}",
                "workflow:org/orders/.github/workflows/release.yml -dispatches-> event:github:org/gitops:deploy "
                        + "{job=notify, payload={service=orders-api}, via=curl}");
        assertThat(sent).filteredOn(l -> l.contains("repository-dispatch action")).singleElement().asString()
                .contains("-dispatches-> event:github:org/gitops:deploy", "service=orders-api").doesNotContain("s3cr3t");

        Path gitops = repo("gitops", """
                name: deploy-service
                on:
                  workflow_call:
                    inputs:
                      service: {type: string}
                      tag: {type: string}
                  repository_dispatch:
                    types: [deploy]
                env:
                  VALUES: charts/services/values-prod.yaml
                jobs:
                  bump:
                    runs-on: ubuntu-latest
                    steps:
                      - run: ./scripts/bump.sh "${{ inputs.service }}" "${{ inputs.tag }}"
                        env:
                          GH_TOKEN: ${{ secrets.K8S_TOKEN }}
                """, "deploy-service.yml");
        Files.createDirectories(gitops.resolve("scripts"));
        Files.writeString(gitops.resolve("scripts/bump.sh"), """
                #!/bin/sh
                SERVICE="$1"; TAG="$2"
                git clone https://x-access-token:${GH_TOKEN}@github.com/org/k8s.git
                cd k8s
                yq -i ".services.${SERVICE}.image.tag = \\"${TAG}\\"" "$VALUES"
                sed -i "s/^appVersion:.*/appVersion: ${TAG}/" charts/services/Chart.yaml
                git commit -am "deploy ${SERVICE} ${TAG}" && git push origin main
                helm upgrade --install "$SERVICE" ./charts/services -n apps --set image.tag="$TAG" -f values-prod.yaml
                """);
        List<String> got = edges("org/gitops", gitops).stream().map(e -> show(e) + " " + e.attrs()).toList();
        assertThat(got).contains(
                "event:github:org/gitops:deploy -triggers-> workflow:org/gitops/.github/workflows/deploy-service.yml {}",
                "workflow:org/gitops/.github/workflows/deploy-service.yml -runs-> script:org/gitops/scripts/bump.sh "
                        + "{args=[${{ inputs.service }}, ${{ inputs.tag }}], env={GH_TOKEN=${{ secrets.K8S_TOKEN }}, VALUES=charts/services/values-prod.yaml}, job=bump}",
                "script:org/gitops/scripts/bump.sh -clones-> repo:org/k8s {}",
                "script:org/gitops/scripts/bump.sh -writes-> file:org/k8s/$VALUES {expression=.services.$1.image.tag = \"$2\", key=.services.$1.image.tag, via=yq}",
                "script:org/gitops/scripts/bump.sh -writes-> file:org/k8s/charts/services/Chart.yaml {expression=s/^appVersion:.*/appVersion: $2/, via=sed}",
                "script:org/gitops/scripts/bump.sh -pushes-> repo:org/k8s {}",
                "script:org/gitops/scripts/bump.sh -installs-> helm-release:$1 {chart=./charts/services, namespace=apps, set={image.tag=$2}, values=[values-prod.yaml]}");
        assertThat(got).as("a token in a URL is not a fact").noneMatch(l -> l.contains("x-access-token"));
    }
}
