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
 * #187: Quartz jobs (a {@code org.quartz.Job}, through a base class of the service too) are jobs with what their
 * {@code execute} does, and the schedule the code builds next to them; found from the source alone (the classpath
 * here has no Quartz).
 */
class QuartzJobsTest {
    @TempDir
    Path tmp;

    private static void write(Path root, String cls, String code) throws IOException {
        Path f = root.resolve("src/main/java/com/example/jobs/" + cls + ".java");
        Files.createDirectories(f.getParent());
        Files.writeString(f, code);
    }

    @Test
    void quartzJobsAreJobsWithTheirEffectsAndSchedule() throws IOException {
        Path root = tmp.resolve("reports");
        Files.createDirectories(root);
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { java }\n");
        write(root, "BaseJob", """
                package com.example.jobs;

                import org.quartz.Job;
                import org.quartz.JobExecutionContext;

                public abstract class BaseJob implements Job {
                    @Override
                    public void execute(JobExecutionContext context) {
                        run();
                    }

                    protected abstract void run();
                }
                """);
        write(root, "DailyReportJob", """
                package com.example.jobs;

                import org.springframework.jdbc.core.JdbcTemplate;

                public class DailyReportJob extends BaseJob {
                    private final JdbcTemplate jdbc;

                    public DailyReportJob(JdbcTemplate jdbc) {
                        this.jdbc = jdbc;
                    }

                    @Override
                    protected void run() {
                        jdbc.update("insert into report.daily (day) values (current_date)");
                    }
                }
                """);
        write(root, "CleanupJob", """
                package com.example.jobs;

                import org.quartz.Job;
                import org.quartz.JobExecutionContext;
                import org.springframework.jdbc.core.JdbcTemplate;

                public class CleanupJob implements Job {
                    private JdbcTemplate jdbc;

                    @Override
                    public void execute(JobExecutionContext context) {
                        jdbc.update("delete from report.daily where day < current_date - 30");
                    }
                }
                """);
        write(root, "Schedules", """
                package com.example.jobs;

                import org.quartz.CronScheduleBuilder;
                import org.quartz.JobBuilder;
                import org.quartz.Scheduler;
                import org.quartz.TriggerBuilder;

                public class Schedules {
                    static final String CLEANUP_CRON = "0 0 3 ? * MON-FRI";

                    void cleanup(Scheduler scheduler) throws Exception {
                        scheduler.scheduleJob(JobBuilder.newJob(CleanupJob.class).withIdentity("cleanup").build(),
                                TriggerBuilder.newTrigger().withSchedule(CronScheduleBuilder.cronSchedule(CLEANUP_CRON)).build());
                    }

                    void report(Scheduler scheduler) throws Exception {
                        scheduler.scheduleJob(JobBuilder.newJob(DailyReportJob.class).build(),
                                TriggerBuilder.newTrigger().withSchedule(CronScheduleBuilder.cronSchedule("0 30 6 * * ?")).build());
                    }
                }
                """);
        StringWriter out = new StringWriter();
        Export.java(root, Fixtures.classpath(), Logast.policy(root, null), "reports", "test", out);
        String facts = out.toString();
        List<String> jobs = facts.lines().filter(l -> l.contains("\"kind\":\"job\"")).toList();
        assertThat(jobs).as(facts).hasSize(2).noneMatch(l -> l.contains("BaseJob"));
        assertThat(jobs).filteredOn(l -> l.contains("job:reports:com.example.jobs.CleanupJob")).singleElement().asString()
                .contains("\"entrypoint\":\"quartz\"", "\"cron\":\"0 0 3 ? * MON-FRI\"");
        assertThat(jobs).filteredOn(l -> l.contains("job:reports:com.example.jobs.DailyReportJob")).singleElement().asString()
                .contains("\"cron\":\"0 30 6 * * ?\"");
        assertThat(facts.lines().filter(l -> l.contains("\"from\":\"job:reports:com.example.jobs.DailyReportJob\"")
                && l.contains("\"kind\":\"writes\"") && l.contains("daily")))
                .as("what the job does, through its base class's execute").isNotEmpty();
        assertThat(facts.lines().filter(l -> l.contains("\"from\":\"job:reports:com.example.jobs.CleanupJob\"")
                && l.contains("\"kind\":\"writes\"") && l.contains("daily"))).isNotEmpty();
    }
}
