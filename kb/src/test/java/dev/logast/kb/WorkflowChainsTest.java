package dev.logast.kb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #214: a GitOps workflow's script writes {@code .services.$1.image.tag} into {@code $VALUES}; through the step's args
 * and env and each caller's {@code with:} / dispatch payload, the caller is what writes which key of which file.
 */
class WorkflowChainsTest {
    @TempDir
    Path tmp;

    @Test
    void eachCallerWritesTheKeyItsValuesResolveTo() throws IOException {
        Files.writeString(tmp.resolve("orders.jsonl"), """
                {"header":{"schema":"kb-facts/0","extractor":"github-actions","repo":"org/orders"}}
                {"edge":{"from":"workflow:org/orders/.github/workflows/release.yml","kind":"calls","to":"workflow:org/gitops/.github/workflows/deploy-service.yml","attrs":{"job":"deploy","ref":"main","with":{"service":"orders-api","tag":"${{ github.sha }}"}},"provenance":"extracted","evidence":{"file":".github/workflows/release.yml","line":6}}}
                """);
        Files.writeString(tmp.resolve("billing.jsonl"), """
                {"header":{"schema":"kb-facts/0","extractor":"github-actions","repo":"org/billing"}}
                {"edge":{"from":"workflow:org/billing/.github/workflows/release.yml","kind":"dispatches","to":"event:github:org/gitops:deploy","attrs":{"job":"notify","payload":{"service":"billing"},"via":"curl"},"provenance":"extracted","evidence":{"file":".github/workflows/release.yml","line":9}}}
                """);
        Files.writeString(tmp.resolve("gitops.jsonl"), """
                {"header":{"schema":"kb-facts/0","extractor":"github-actions","repo":"org/gitops"}}
                {"edge":{"from":"event:github:org/gitops:deploy","kind":"triggers","to":"workflow:org/gitops/.github/workflows/deploy-event.yml","provenance":"extracted","evidence":{"file":".github/workflows/deploy-event.yml","line":4}}}
                {"edge":{"from":"workflow:org/gitops/.github/workflows/deploy-service.yml","kind":"runs","to":"script:org/gitops/scripts/bump.sh","attrs":{"args":["${{ inputs.service }}","${{ inputs.tag }}"],"env":{"VALUES":"charts/services/values-prod.yaml"},"job":"bump"},"provenance":"extracted","evidence":{"file":".github/workflows/deploy-service.yml","line":14}}}
                {"edge":{"from":"workflow:org/gitops/.github/workflows/deploy-event.yml","kind":"runs","to":"script:org/gitops/scripts/bump.sh","attrs":{"args":["${{ github.event.client_payload.service }}","latest"],"env":{"VALUES":"charts/services/values-prod.yaml"},"job":"bump"},"provenance":"extracted","evidence":{"file":".github/workflows/deploy-event.yml","line":10}}}
                {"edge":{"from":"script:org/gitops/scripts/bump.sh","kind":"writes","to":"file:org/k8s/$VALUES","attrs":{"expression":".services.$1.image.tag = \\"$2\\"","key":".services.$1.image.tag","via":"yq"},"provenance":"extracted","evidence":{"file":"scripts/bump.sh","line":5}}}
                {"edge":{"from":"script:org/gitops/scripts/bump.sh","kind":"pushes","to":"repo:org/k8s","provenance":"extracted","evidence":{"file":"scripts/bump.sh","line":7}}}
                """);
        KnowledgeBase kb = KnowledgeBase.load(List.of(tmp));
        assertThat(kb.neighbors("workflow:org/orders/.github/workflows/release.yml", "out", List.of("writes"), 10))
                .as("a reusable call's with:")
                .contains("file:org/k8s/charts/services/values-prod.yaml", "key=.services.orders-api.image.tag", "[inferred via workflow-chain]",
                        "through=[workflow:org/gitops/.github/workflows/deploy-service.yml, script:org/gitops/scripts/bump.sh]");
        assertThat(kb.neighbors("workflow:org/billing/.github/workflows/release.yml", "out", List.of("writes"), 10))
                .as("a repository dispatch's payload").contains("key=.services.billing.image.tag").doesNotContain("orders-api");
        assertThat(kb.neighbors("file:org/k8s/charts/services/values-prod.yaml", "in", List.of("writes"), 10))
                .as("who writes the values file").contains("org/orders", "org/billing");
        assertThat(kb.neighbors("repo:org/k8s", "in", List.of("pushes"), 10)).as("nothing to resolve: no derived edge")
                .doesNotContain("workflow-chain");
    }
}
