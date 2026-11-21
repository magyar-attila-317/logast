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
 * #211: one startup listener runs whichever subclass of an abstract job the active profile makes a bean (one job per
 * container start): each profile is a job with what its own class does; profiles named by a constant count.
 */
class StartupJobsTest {
    @TempDir
    Path tmp;

    private static void write(Path root, String cls, String code) throws IOException {
        Path f = root.resolve("src/main/java/com/example/jobs/" + cls + ".java");
        Files.createDirectories(f.getParent());
        Files.writeString(f, code);
    }

    private static String export(Path root) throws IOException {
        StringWriter out = new StringWriter();
        Export.java(root, Fixtures.classpath(), Logast.policy(root, null), "billing", "test", out);
        return out.toString();
    }

    private static Path service(Path tmp) throws IOException {
        Path root = tmp.resolve("billing");
        Files.createDirectories(root);
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { java }\n");
        write(root, "JobNames", """
                package com.example.jobs;

                public final class JobNames {
                    public static final String INVOICES = "invoiceJob";
                    public static final String REMINDERS = "reminderJob";
                }
                """);
        write(root, "BillingJob", """
                package com.example.jobs;

                public abstract class BillingJob {
                    public void execute() {
                        doExecute("GB");
                    }

                    protected abstract void doExecute(String country);
                }
                """);
        write(root, "JobRunner", """
                package com.example.jobs;

                import org.springframework.boot.context.event.ApplicationReadyEvent;
                import org.springframework.context.event.EventListener;
                import org.springframework.stereotype.Component;

                @Component
                public class JobRunner {
                    private final BillingJob job;

                    public JobRunner(BillingJob job) {
                        this.job = job;
                    }

                    @EventListener(ApplicationReadyEvent.class)
                    public void start() {
                        job.execute();
                    }
                }
                """);
        write(root, "InvoiceJob", """
                package com.example.jobs;

                import org.springframework.context.annotation.Profile;
                import org.springframework.jdbc.core.JdbcTemplate;
                import org.springframework.stereotype.Component;

                @Component
                @Profile(JobNames.INVOICES)
                public class InvoiceJob extends BillingJob {
                    private final JdbcTemplate jdbc;

                    public InvoiceJob(JdbcTemplate jdbc) {
                        this.jdbc = jdbc;
                    }

                    @Override
                    protected void doExecute(String country) {
                        jdbc.update("insert into billing.invoice (country) values (?)", country);
                    }
                }
                """);
        write(root, "ReminderJob", """
                package com.example.jobs;

                import org.springframework.context.annotation.Profile;
                import org.springframework.jdbc.core.JdbcTemplate;
                import org.springframework.stereotype.Component;

                @Component
                @Profile(JobNames.REMINDERS)
                public class ReminderJob extends BillingJob {
                    private final JdbcTemplate jdbc;

                    public ReminderJob(JdbcTemplate jdbc) {
                        this.jdbc = jdbc;
                    }

                    @Override
                    protected void doExecute(String country) {
                        jdbc.update("update billing.reminder set sent = true where country = ?", country);
                    }
                }
                """);
        return root;
    }

    @Test
    void eachProfiledJobAStartupListenerRunsIsAJob() throws IOException {
        String facts = export(service(tmp));
        List<String> jobs = facts.lines().filter(l -> l.contains("\"kind\":\"job\"")).toList();
        assertThat(jobs).as(facts).hasSize(2);
        assertThat(jobs).filteredOn(l -> l.contains("\"id\":\"job:billing:invoiceJob\"")).singleElement().asString()
                .contains("\"entrypoint\":\"startup\"", "\"profile\":\"invoiceJob\"", "\"implementation\":\"com.example.jobs.InvoiceJob\"",
                        "\"handler\":\"com.example.jobs.JobRunner.start\"");
        List<String> invoiceWrites = facts.lines().filter(l -> l.contains("\"from\":\"job:billing:invoiceJob\"") && l.contains("\"kind\":\"writes\"")).toList();
        assertThat(invoiceWrites).as(facts).anyMatch(l -> l.contains("billing.invoice")).noneMatch(l -> l.contains("reminder"));
        assertThat(facts.lines().filter(l -> l.contains("\"from\":\"job:billing:reminderJob\"") && l.contains("\"kind\":\"writes\"")))
                .anyMatch(l -> l.contains("billing.reminder")).noneMatch(l -> l.contains("invoice"));
        assertThat(facts).as("the profile named by a constant").contains("\"to\":\"profile:billing:invoiceJob\"");
    }

    /** Beans chosen by an environment profile (local, prod, …) are configuration, not jobs. */
    @Test
    void environmentProfilesAreNotJobs() throws IOException {
        Path root = service(tmp);
        for (String[] f : List.of(new String[]{"InvoiceJob", "JobNames.INVOICES", "\"local\""}, new String[]{"ReminderJob", "JobNames.REMINDERS", "\"prod\""})) {
            Path p = root.resolve("src/main/java/com/example/jobs/" + f[0] + ".java");
            Files.writeString(p, Files.readString(p).replace(f[1], f[2]));
        }
        String facts = export(root);
        assertThat(facts.lines().filter(l -> l.contains("\"kind\":\"job\""))).as(facts).isEmpty();
    }
}
