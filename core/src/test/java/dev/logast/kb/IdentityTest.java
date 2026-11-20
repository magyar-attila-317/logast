package dev.logast.kb;

import dev.logast.export.JavaExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** #161: which deployments run which service's code. */
class IdentityTest {
    @TempDir
    Path tmp;

    @Test
    void servicesAreLinkedToTheirDeploymentsByImageNameOrImageName() throws IOException {
        Files.writeString(tmp.resolve("code.jsonl"), """
                {"header":{"schema":"kb-facts/0","extractor":"java","repo":"services"}}
                {"node":{"id":"service:orders","kind":"service","attrs":{"image":"registry.example.com/shop/orders-api:1.0"},"evidence":{"file":"orders/build.gradle.kts"}}}
                {"node":{"id":"service:shipping","kind":"service","evidence":{"file":"shipping/build.gradle.kts"}}}
                {"node":{"id":"service:billing","kind":"service","evidence":{"file":"billing/build.gradle.kts"}}}
                {"node":{"id":"service:ledger","kind":"service","attrs":{"application":"ledger"},"evidence":{"file":"ledger/build.gradle.kts"}}}
                {"node":{"id":"service:ledger-batch","kind":"service","attrs":{"application":"ledger"},"evidence":{"file":"ledger-batch/build.gradle.kts"}}}
                """);
        Files.writeString(tmp.resolve("deploy.jsonl"), """
                {"header":{"schema":"kb-facts/0","extractor":"helm","repo":"deploy"}}
                {"node":{"id":"deployment:orders-api","kind":"deployment","env":"prod","evidence":{"file":"charts/orders/templates/deployment.yaml"}}}
                {"edge":{"from":"deployment:orders-api","kind":"deploys","to":"image:registry.example.com/shop/orders-api","env":"prod","provenance":"extracted","evidence":{"file":"charts/orders/templates/deployment.yaml"}}}
                {"node":{"id":"deployment:shipping","kind":"deployment","env":"prod","evidence":{"file":"charts/shipping/templates/deployment.yaml"}}}
                {"node":{"id":"deployment:billing-worker","kind":"deployment","env":"prod","evidence":{"file":"charts/billing/templates/deployment.yaml"}}}
                {"edge":{"from":"deployment:billing-worker","kind":"deploys","to":"image:registry.example.com/shop/billing","env":"prod","provenance":"extracted","evidence":{"file":"charts/billing/templates/deployment.yaml"}}}
                {"node":{"id":"job:platform:billing-cleanup","kind":"job","env":"prod","attrs":{"entrypoint":"cronjob","schedule":"0 3 * * *"},"evidence":{"file":"charts/billing/templates/cronjob.yaml"}}}
                {"edge":{"from":"job:platform:billing-cleanup","kind":"deploys","to":"image:registry.example.com/shop/billing","env":"prod","provenance":"extracted","evidence":{"file":"charts/billing/templates/cronjob.yaml"}}}
                {"node":{"id":"deployment:ledger","kind":"deployment","env":"prod","evidence":{"file":"charts/ledger/templates/deployment.yaml"}}}
                """);
        KnowledgeBase kb = KnowledgeBase.load(List.of(tmp));
        assertThat(kb.problems()).isEmpty();
        assertThat(kb.neighbors("service:orders", "out", List.of("deployed-as"), 10)).as("the image the build pushes")
                .contains("-deployed-as-> deployment:orders-api [prod] [inferred via identity:image]");
        assertThat(kb.neighbors("service:shipping", "out", List.of("deployed-as"), 10)).as("the same name")
                .contains("deployment:shipping [prod] [inferred via identity:name]");
        assertThat(kb.neighbors("service:billing", "out", List.of("deployed-as"), 10)).as("the image's last segment, a CronJob too")
                .contains("deployment:billing-worker [prod] [inferred via identity:image-name]", "job:platform:billing-cleanup [prod] [inferred via identity:image-name]");
        assertThat(kb.neighbors("deployment:ledger", "in", List.of("deployed-as"), 10)).as("two services named ledger: none linked")
                .doesNotContain("deployed-as");
        assertThat(kb.ambiguous()).singleElement().asString().contains("deployment:ledger [prod]", "service:ledger", "service:ledger-batch");
        assertThat(kb.summary()).contains("4 deployments linked", "not linked, more than one service fits");
        assertThat(kb.path("service:orders", "image:registry.example.com/shop/orders-api", 4)).contains("-deployed-as->", "-deploys->");
    }

    @Test
    void theImageABuildPushes() throws IOException {
        Files.writeString(tmp.resolve("build.gradle.kts"), """
                plugins { id("com.google.cloud.tools.jib") version "3.4.5" }
                jib {
                    from { image = "eclipse-temurin:21-jre" }
                    to {
                        image = "registry.example.com/shop/orders"
                        tags = setOf("latest")
                    }
                }
                """);
        assertThat(JavaExtractor.builtImage(tmp)).as("to, not from").isEqualTo("registry.example.com/shop/orders");
        Files.writeString(tmp.resolve("build.gradle.kts"), "tasks.bootBuildImage {\n    imageName.set(\"registry.example.com/shop/billing:1\")\n}\n");
        assertThat(JavaExtractor.builtImage(tmp)).isEqualTo("registry.example.com/shop/billing:1");
        Files.writeString(tmp.resolve("build.gradle.kts"), "jib { to { image = \"registry/${project.name}\" } }\n");
        assertThat(JavaExtractor.builtImage(tmp)).as("a computed name is not guessed").isNull();
    }
}
