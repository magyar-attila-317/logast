package dev.logast.kb;

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

    /** #211: a CronJob that starts the service with a job's profile active runs that job. */
    @Test
    void aWorkloadRunsTheJobOfTheProfileItActivates() throws IOException {
        Files.writeString(tmp.resolve("code.jsonl"), """
                {"header":{"schema":"kb-facts/0","extractor":"java","repo":"billing"}}
                {"node":{"id":"service:billing","kind":"service","evidence":{"file":"build.gradle.kts"}}}
                {"node":{"id":"job:billing:invoiceJob","kind":"job","attrs":{"entrypoint":"startup","profile":"invoiceJob"},"evidence":{"file":"InvoiceJob.java"}}}
                {"node":{"id":"job:billing:reminderJob","kind":"job","attrs":{"entrypoint":"startup","profile":"reminderJob"},"evidence":{"file":"ReminderJob.java"}}}
                """);
        Files.writeString(tmp.resolve("deploy.jsonl"), """
                {"header":{"schema":"kb-facts/0","extractor":"helm","repo":"deploy"}}
                {"node":{"id":"job:platform:billing-invoicejob","kind":"job","env":"prod","attrs":{"entrypoint":"cronjob","schedule":"0 1 * * *","env":{"SPRING_PROFILES_ACTIVE":"prod,invoiceJob"}},"evidence":{"file":"charts/billing/templates/job.yaml"}}}
                {"edge":{"from":"job:platform:billing-invoicejob","kind":"deploys","to":"image:registry.example.com/shop/billing","env":"prod","provenance":"extracted","evidence":{"file":"charts/billing/templates/job.yaml"}}}
                {"node":{"id":"job:platform:reminders","kind":"job","env":"prod","attrs":{"entrypoint":"cronjob","schedule":"0 2 * * *","env":{"SPRING_PROFILES_ACTIVE":"prod, reminderJob"}},"evidence":{"file":"charts/other/templates/job.yaml"}}}
                {"node":{"id":"job:platform:warmup","kind":"job","env":"prod","attrs":{"entrypoint":"cronjob","schedule":"0 3 * * *","env":{"SPRING_PROFILES_ACTIVE":"prod"}},"evidence":{"file":"charts/other/templates/warmup.yaml"}}}
                """);
        KnowledgeBase kb = KnowledgeBase.load(List.of(tmp));
        assertThat(kb.neighbors("job:billing:invoiceJob", "in", List.of("runs"), 10)).as("through the service the CronJob is linked to")
                .contains("job:platform:billing-invoicejob -runs-> {profile=invoiceJob} [prod] [inferred via identity:profile]");
        assertThat(kb.neighbors("job:billing:reminderJob", "in", List.of("runs"), 10)).as("not linked: the one service with that profile")
                .contains("job:platform:reminders -runs-> {profile=reminderJob} [prod] [inferred via identity:profile-name]");
        assertThat(kb.neighbors("job:platform:warmup", "out", List.of("runs"), 10)).as("an environment profile only").doesNotContain("-runs->");
    }
}
