package dev.logast.export;

import dev.logast.Fixtures;
import dev.logast.Logast;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #242: a {@code @Scheduled} cron with a placeholder is resolved per profile as Spring cascades the configuration (the
 * base, {@code application-<profile>.yml}, documents activated for the profile), so a job's schedule in prod is prod's.
 */
class ScheduledProfilesTest {
    @TempDir
    Path tmp;

    @Test
    void aPlaceholderCronIsResolvedPerProfile() throws IOException {
        Path root = tmp.resolve("billing");
        Path src = Files.createDirectories(root.resolve("src/main/java/com/example/billing"));
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { java }\n");
        Files.writeString(src.resolve("InvoiceRunJob.java"), """
                package com.example.billing;

                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;

                @Component
                public class InvoiceRunJob {
                    @Scheduled(cron = "${billing.invoice-run-cron}")
                    public void issueInvoices() {
                    }

                    @Scheduled(cron = "${billing.cleanup-cron:0 0 3 * * *}")
                    public void cleanUp() {
                    }
                }
                """);
        Path res = Files.createDirectories(root.resolve("src/main/resources"));
        Files.writeString(res.resolve("application.yml"), """
                billing:
                  invoice-run-cron: "0 0 * * * *"
                ---
                spring:
                  config:
                    activate:
                      on-profile: staging
                billing:
                  invoice-run-cron: "0 0 12 * * *"
                """);
        Files.writeString(res.resolve("application-prod.yml"), """
                billing:
                  invoice-run-cron: "0 30 4 * * MON"
                """);
        Files.writeString(res.resolve("application-dev.yml"), "logging:\n  level:\n    root: DEBUG\n");
        StringWriter out = new StringWriter();
        Export.java(root, Fixtures.classpath(), Logast.policy(root, null), "billing", "test", out);
        List<String> jobs = out.toString().lines().filter(l -> l.contains("\"kind\":\"job\"")).toList();
        assertThat(jobs).filteredOn(l -> l.contains("issueInvoices")).as(out.toString()).singleElement().asString()
                .contains("\"cronResolved\":\"0 0 * * * *\"", "\"cronByProfile\":{\"prod\":\"0 30 4 * * MON\",\"staging\":\"0 0 12 * * *\"}");
        assertThat(jobs).filteredOn(l -> l.contains("cleanUp")).singleElement().asString()
                .as("no profile changes it: no per-profile entry").contains("\"cronResolved\":\"0 0 3 * * *\"").doesNotContain("cronByProfile");
    }
}
