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

    /** #189: schedules the configuration binds to the jobs (application.yml, a @ConfigurationProperties class), per profile. */
    @Test
    void schedulesFromTheConfigurationPerProfile() throws IOException {
        Path root = tmp.resolve("batch");
        Files.createDirectories(root);
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { java }\n");
        for (String job : List.of("ArchiveJob", "PurgeJob", "ReportJob")) {
            write(root, job, """
                    package com.example.jobs;

                    import org.quartz.Job;
                    import org.quartz.JobExecutionContext;

                    public class %s implements Job {
                        @Override
                        public void execute(JobExecutionContext context) {
                        }
                    }
                    """.formatted(job));
        }
        write(root, "JobsProperties", """
                package com.example.jobs;

                import java.util.List;
                import org.springframework.boot.context.properties.ConfigurationProperties;

                @ConfigurationProperties("batch")
                public record JobsProperties(List<Definition> jobs) {
                    public record Definition(String jobClass, String name, String cron) {
                    }
                }
                """);
        Path res = Files.createDirectories(root.resolve("src/main/resources"));
        Files.writeString(res.resolve("application.yml"), """
                batch:
                  jobs:
                    - job-class: com.example.jobs.ArchiveJob
                      cron: "0 15 1 * * ?"
                    - name: purge
                      cron: "0 0 4 ? * SUN"
                scheduler:
                  report-job:
                    cron-expression: "0 30 6 * * ?"
                    enabled: true
                """);
        Files.writeString(res.resolve("application-prod.yml"), """
                batch:
                  jobs:
                    - job-class: com.example.jobs.ArchiveJob
                      cron: "0 15 2 * * ?"
                    - name: purge
                      cron: "0 0 4 ? * SUN"
                """);
        StringWriter out = new StringWriter();
        Export.java(root, Fixtures.classpath(), Logast.policy(root, null), "batch", "test", out);
        List<String> jobs = out.toString().lines().filter(l -> l.contains("\"kind\":\"job\"")).toList();
        assertThat(jobs).as(out.toString()).hasSize(3);
        assertThat(jobs).filteredOn(l -> l.contains("ArchiveJob")).singleElement().asString()
                .contains("\"cron\":\"0 15 1 * * ?\"", "\"cronByProfile\":{\"prod\":\"0 15 2 * * ?\"}",
                        "\"cronFrom\":\"application config: batch.jobs[0].cron\"");
        assertThat(jobs).filteredOn(l -> l.contains("PurgeJob")).singleElement().asString()
                .contains("\"cron\":\"0 0 4 ? * SUN\"").doesNotContain("cronByProfile");
        assertThat(jobs).filteredOn(l -> l.contains("ReportJob")).singleElement().asString()
                .contains("\"cron\":\"0 30 6 * * ?\"", "scheduler.report-job.cron-expression");
    }

    /** #190: jobs named through an enum constant that carries their metadata. */
    @Test
    void jobsNamedThroughAnEnumConstant() throws IOException {
        Path root = tmp.resolve("tasks");
        Files.createDirectories(root);
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { java }\n");
        write(root, "JobType", """
                package com.example.jobs;

                import org.quartz.Job;

                public enum JobType {
                    CLEANUP("cleanup", CleanupJob.class, "0 0 3 ? * MON-FRI"),
                    EXPORT("export", ExportJob.class, "0 0 1 * * ?"),
                    ARCHIVE("archive");

                    JobType(String name, Class<? extends Job> job, String cron) {
                    }

                    JobType(String name) {
                    }
                }
                """);
        for (String job : List.of("CleanupJob", "ExportJob")) {
            write(root, job, """
                    package com.example.jobs;

                    import org.quartz.Job;
                    import org.quartz.JobExecutionContext;

                    public class %s implements Job {
                        @Override
                        public void execute(JobExecutionContext context) {
                        }
                    }
                    """.formatted(job));
        }
        write(root, "ArchiveDataJob", """
                package com.example.jobs;

                import org.quartz.Job;
                import org.quartz.JobExecutionContext;

                public class ArchiveDataJob implements Job {
                    public JobType type() {
                        return JobType.ARCHIVE;
                    }

                    @Override
                    public void execute(JobExecutionContext context) {
                    }
                }
                """);
        Path res = Files.createDirectories(root.resolve("src/main/resources"));
        Files.writeString(res.resolve("application.yml"), """
                tasks:
                  definitions:
                    cleanup:
                      cron: "0 0 5 ? * MON-FRI"
                    archive:
                      cron: "0 0 2 * * ?"
                      enabled: true
                """);
        StringWriter out = new StringWriter();
        Export.java(root, Fixtures.classpath(), Logast.policy(root, null), "tasks", "test", out);
        List<String> jobs = out.toString().lines().filter(l -> l.contains("\"kind\":\"job\"")).toList();
        assertThat(jobs).as(out.toString()).hasSize(3);
        assertThat(jobs).filteredOn(l -> l.contains("CleanupJob")).singleElement().asString()
                .as("the configuration's cron wins over the enum's").contains("\"cron\":\"0 0 5 ? * MON-FRI\"",
                        "\"enumConstant\":\"JobType.CLEANUP\"", "tasks.definitions.cleanup.cron");
        assertThat(jobs).filteredOn(l -> l.contains("ExportJob")).singleElement().asString()
                .contains("\"cron\":\"0 0 1 * * ?\"", "\"cronFrom\":\"enum JobType.EXPORT\"");
        assertThat(jobs).filteredOn(l -> l.contains("ArchiveDataJob")).singleElement().asString()
                .as("the constant its class refers to").contains("\"cron\":\"0 0 2 * * ?\"", "\"enumConstant\":\"JobType.ARCHIVE\"");
    }

    /**
     * #197: a job's entry names it ({@code name: StockEnrichmentJob}) and lists its triggers, each with its own cron, as
     * {@code @ConfigurationProperties} bind them and a configuration class schedules them with CronTriggerFactoryBean.
     */
    @Test
    void triggersListedUnderTheJobsEntry() throws IOException {
        Path root = tmp.resolve("stock");
        Files.createDirectories(root);
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { java }\n");
        for (String job : List.of("StockEnrichmentJob", "StockImportJob")) {
            write(root, job, """
                    package com.example.jobs;

                    import org.quartz.Job;
                    import org.quartz.JobExecutionContext;

                    public class %s implements Job {
                        @Override
                        public void execute(JobExecutionContext context) {
                        }
                    }
                    """.formatted(job));
        }
        write(root, "StockJobConfig", """
                package com.example.jobs;

                import org.springframework.scheduling.quartz.CronTriggerFactoryBean;
                import org.springframework.scheduling.quartz.JobDetailFactoryBean;

                public class StockJobConfig {
                    JobDetailFactoryBean enrichmentDetail() {
                        JobDetailFactoryBean f = new JobDetailFactoryBean();
                        f.setJobClass(StockEnrichmentJob.class);
                        return f;
                    }

                    CronTriggerFactoryBean northTrigger(Trigger jobTrigger) {
                        CronTriggerFactoryBean t = new CronTriggerFactoryBean();
                        t.setCronExpression(jobTrigger.getCron());
                        return t;
                    }

                    record Trigger(String name, String cron) {
                        String getCron() {
                            return cron;
                        }
                    }
                }
                """);
        Path res = Files.createDirectories(root.resolve("src/main/resources"));
        Files.writeString(res.resolve("application.yml"), """
                shop:
                  jobs:
                    stock-enrichment:
                      name: "StockEnrichmentJob"
                      group: "StockJobs"
                      triggers:
                        - name: "Stock_North_EveryDay"
                          cron: "0 0 10 ? * * *"
                          region: "north"
                        - name: "Stock_South_EveryDay"
                          cron: "0 10 10 ? * * *"
                          region: "south"
                      max-retries: 30
                    stock-import:
                      name: "StockImportJob"
                      batch-size: 10000
                """);
        StringWriter out = new StringWriter();
        Export.java(root, Fixtures.classpath(), Logast.policy(root, null), "stock", "test", out);
        List<String> jobs = out.toString().lines().filter(l -> l.contains("\"kind\":\"job\"")).toList();
        assertThat(jobs).as(out.toString()).hasSize(2);
        assertThat(jobs).filteredOn(l -> l.contains("StockEnrichmentJob")).singleElement().asString()
                .contains("\"cron\":\"0 0 10 ? * * *\"", "\"name\":\"Stock_North_EveryDay\"", "\"name\":\"Stock_South_EveryDay\"",
                        "\"cron\":\"0 10 10 ? * * *\"", "\"region\":\"south\"", "shop.jobs.stock-enrichment.triggers[1].cron");
        assertThat(jobs).filteredOn(l -> l.contains("StockImportJob")).singleElement().asString().doesNotContain("cron");
    }

    /**
     * #198: the cron followed back from the code that sets it: a CronTriggerFactoryBean's {@code setCronExpression} of a
     * trigger chosen by name from a {@code @ConfigurationProperties} list, its job from the {@code setJobDetail} bean's
     * {@code setJobClass}; the configuration names the job nowhere.
     */
    @Test
    void theCronIsFollowedBackFromTheCodeThatSetsIt() throws IOException {
        Path root = tmp.resolve("parcels");
        Files.createDirectories(root);
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { java }\n");
        write(root, "ParcelSyncJob", """
                package com.example.jobs;

                import org.quartz.Job;
                import org.quartz.JobExecutionContext;

                public class ParcelSyncJob implements Job {
                    @Override
                    public void execute(JobExecutionContext context) {
                    }
                }
                """);
        write(root, "ParcelJobConfig", """
                package com.example.jobs;

                import java.util.List;
                import org.quartz.JobDetail;
                import org.springframework.boot.context.properties.ConfigurationProperties;
                import org.springframework.scheduling.quartz.CronTriggerFactoryBean;
                import org.springframework.scheduling.quartz.JobDetailFactoryBean;

                public class ParcelJobConfig {
                    private static final String NIGHT_TRIGGER = "Sync_Night";
                    private static final java.util.TimeZone HOME_ZONE = java.util.TimeZone.getTimeZone("Europe/London");
                    private final SyncProperties syncProperties;

                    ParcelJobConfig(SyncProperties syncProperties) {
                        this.syncProperties = syncProperties;
                    }

                    public JobDetailFactoryBean syncJobDetail() {
                        JobDetailFactoryBean f = new JobDetailFactoryBean();
                        f.setJobClass(ParcelSyncJob.class);
                        return f;
                    }

                    public CronTriggerFactoryBean nightTrigger(final JobDetail syncJobDetail) {
                        JobTrigger jobTrigger = syncProperties.getTriggers().stream().filter(t -> t.getName().equals(NIGHT_TRIGGER))
                                .findAny().orElseThrow();
                        CronTriggerFactoryBean t = new CronTriggerFactoryBean();
                        t.setJobDetail(syncJobDetail);
                        t.setCronExpression(jobTrigger.getCron());
                        t.setTimeZone(HOME_ZONE);
                        return t;
                    }

                    @ConfigurationProperties("logistics.scheduling.sync")
                    public static class SyncProperties {
                        private List<JobTrigger> triggers;

                        public List<JobTrigger> getTriggers() {
                            return triggers;
                        }
                    }

                    public static class JobTrigger {
                        private String name;
                        private String cron;

                        public String getName() {
                            return name;
                        }

                        public String getCron() {
                            return cron;
                        }
                    }
                }
                """);
        Path res = Files.createDirectories(root.resolve("src/main/resources"));
        Files.writeString(res.resolve("application.yml"), """
                logistics:
                  scheduling:
                    sync:
                      triggers:
                        - name: "Sync_Day"
                          cron: "0 0 12 ? * MON-FRI"
                        - name: "Sync_Night"
                          cron: "0 30 1 ? * *"
                """);
        StringWriter out = new StringWriter();
        Export.java(root, Fixtures.classpath(), Logast.policy(root, null), "parcels", "test", out);
        List<String> jobs = out.toString().lines().filter(l -> l.contains("\"kind\":\"job\"")).toList();
        assertThat(jobs).as(out.toString()).singleElement().asString()
                .as("the trigger the code picks by name, not the first one").contains("\"cron\":\"0 30 1 ? * *\"",
                        "\"cronFrom\":\"logistics.scheduling.sync.triggers[1].cron\"", "\"zone\":\"Europe/London\"").doesNotContain("0 0 12");
    }

    /**
     * #208: the jobs a JobDetailFactoryBean defines: one per JobDetail (one class run as several jobs, each with its name,
     * its data and its trigger), a class whose base class is a library's (no Quartz import, no classpath: named only where
     * it is scheduled), and a class handed to a helper that builds the JobDetail.
     */
    @Test
    void eachJobDetailIsAJob() throws IOException {
        Path root = tmp.resolve("ledger");
        Files.createDirectories(root);
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { java }\n");
        write(root, "TallyJob", """
                package com.example.jobs;

                import com.example.platform.PlatformJob;

                public class TallyJob extends PlatformJob {
                    @Override
                    protected void runJob(CalcType type) {
                    }
                }
                """);
        write(root, "CleanupJob", """
                package com.example.jobs;

                import com.example.platform.PlatformJob;

                public class CleanupJob extends PlatformJob {
                    @Override
                    protected void runJob(CalcType type) {
                    }
                }
                """);
        write(root, "CalcType", """
                package com.example.jobs;

                public enum CalcType { DAILY, MONTHLY }
                """);
        write(root, "LedgerJobConfig", """
                package com.example.jobs;

                import java.util.Map;
                import org.quartz.Job;
                import org.quartz.JobDetail;
                import org.springframework.context.annotation.Bean;
                import org.springframework.scheduling.quartz.CronTriggerFactoryBean;
                import org.springframework.scheduling.quartz.JobDetailFactoryBean;

                public class LedgerJobConfig {
                    @Bean
                    public JobDetailFactoryBean dailyTallyDetail() {
                        JobDetailFactoryBean f = new JobDetailFactoryBean();
                        f.setJobClass(TallyJob.class);
                        f.setName("daily-tally");
                        f.setJobDataAsMap(Map.of("type", CalcType.DAILY));
                        return f;
                    }

                    @Bean
                    public JobDetailFactoryBean monthlyTallyDetail() {
                        JobDetailFactoryBean f = new JobDetailFactoryBean();
                        f.setJobClass(TallyJob.class);
                        f.setName("monthly-tally");
                        f.setJobDataAsMap(Map.of("type", CalcType.MONTHLY));
                        return f;
                    }

                    @Bean
                    public JobDetailFactoryBean cleanupDetail() {
                        return detail(CleanupJob.class, "cleanup");
                    }

                    @Bean
                    public CronTriggerFactoryBean dailyTallyTrigger(JobDetail dailyTallyDetail) {
                        CronTriggerFactoryBean t = new CronTriggerFactoryBean();
                        t.setJobDetail(dailyTallyDetail);
                        t.setCronExpression("0 0 2 * * ?");
                        return t;
                    }

                    @Bean
                    public CronTriggerFactoryBean monthlyTallyTrigger() {
                        CronTriggerFactoryBean t = new CronTriggerFactoryBean();
                        t.setJobDetail(monthlyTallyDetail().getObject());
                        t.setCronExpression("0 0 3 1 * ?");
                        return t;
                    }

                    @Bean
                    public CronTriggerFactoryBean cleanupTrigger(JobDetail cleanupDetail) {
                        CronTriggerFactoryBean t = new CronTriggerFactoryBean();
                        t.setJobDetail(cleanupDetail);
                        t.setCronExpression("0 30 4 * * ?");
                        return t;
                    }

                    void rescheduleCleanup(org.quartz.Scheduler scheduler) throws Exception {   // mentions it: no JobDetail of its own
                        scheduler.addJob(org.quartz.JobBuilder.newJob(CleanupJob.class).build(), true);
                    }

                    private static JobDetailFactoryBean detail(Class<? extends Job> type, String name) {
                        JobDetailFactoryBean f = new JobDetailFactoryBean();
                        f.setJobClass(type);
                        f.setName(name);
                        return f;
                    }
                }
                """);
        write(root, "ReportStream", """
                package com.example.jobs;

                import java.util.List;

                public class ReportStream {   // ofType/newJob-like calls outside Quartz name no job
                    List<Object> reports(reactor.core.publisher.Flux<Object> all) {
                        return all.ofType(Report.class).collectList().block();
                    }

                    static class Report {
                    }
                }
                """);
        StringWriter out = new StringWriter();
        Export.java(root, Fixtures.classpath(), Logast.policy(root, null), "ledger", "test", out);
        List<String> jobs = out.toString().lines().filter(l -> l.contains("\"kind\":\"job\"")).toList();
        assertThat(jobs).as(out.toString()).hasSize(3);
        assertThat(jobs).filteredOn(l -> l.contains("TallyJob#daily-tally")).singleElement().asString()
                .contains("\"cron\":\"0 0 2 * * ?\"", "\"jobName\":\"daily-tally\"", "CalcType.DAILY");
        assertThat(jobs).filteredOn(l -> l.contains("TallyJob#monthly-tally")).singleElement().asString()
                .contains("\"cron\":\"0 0 3 1 * ?\"", "CalcType.MONTHLY");
        assertThat(jobs).filteredOn(l -> l.contains("job:ledger:com.example.jobs.CleanupJob\"")).singleElement().asString()
                .contains("\"cron\":\"0 30 4 * * ?\"");
        assertThat(jobs).noneMatch(l -> l.contains("Report"));
    }

    /**
     * #209: a trigger in the configuration that starts a chain of jobs: its cron above, the jobs listed below it by enum
     * constant ({@code execution-plans[i].jobChain[j].name}); each job gets every trigger it is in, with its place in
     * the chain and what its plan says.
     */
    @Test
    void jobsChainedUnderATriggerGetItsSchedule() throws IOException {
        Path root = tmp.resolve("totals");
        Files.createDirectories(root);
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { java }\n");
        for (String job : List.of("ViewRefreshJob", "WeeklyTotalsJob", "DailyTotalsJob")) {
            write(root, job, """
                    package com.example.jobs;

                    import org.quartz.Job;
                    import org.quartz.JobExecutionContext;

                    public class %s implements Job {
                        @Override
                        public void execute(JobExecutionContext context) {
                        }
                    }
                    """.formatted(job));
        }
        write(root, "TotalsJobType", """
                package com.example.jobs;

                import org.quartz.Job;

                public enum TotalsJobType {
                    VIEW_REFRESH(ViewRefreshJob.class, "ViewRefreshJob"),
                    WEEKLY_TOTALS(WeeklyTotalsJob.class, "WeeklyTotalsJob"),
                    DAILY_TOTALS(DailyTotalsJob.class, "DailyTotalsJob");

                    private final Class<? extends Job> jobClass;
                    private final String key;

                    TotalsJobType(Class<? extends Job> jobClass, String key) {
                        this.jobClass = jobClass;
                        this.key = key;
                    }
                }
                """);
        Path res = Files.createDirectories(root.resolve("src/main/resources"));
        Files.writeString(res.resolve("application.yml"), """
                totals:
                  run-triggers:
                    monday-north:
                      name: "MondayRunNorth"
                      cron: "0 45 1 ? * 2"
                      trigger-group: "RunTriggersNorth"
                      execution-plans:
                        - description: North jobs for last week
                          region-code: N
                          jobChain:
                            - name: VIEW_REFRESH
                            - name: WEEKLY_TOTALS
                              send-email: true
                    monday-south:
                      name: "MondayRunSouth"
                      cron: "0 15 2 ? * 2"
                      trigger-group: "RunTriggersSouth"
                      execution-plans:
                        - description: South jobs for last week
                          region-code: S
                          jobChain:
                            - name: WEEKLY_TOTALS
                """);
        StringWriter out = new StringWriter();
        Export.java(root, Fixtures.classpath(), Logast.policy(root, null), "totals", "test", out);
        List<String> jobs = out.toString().lines().filter(l -> l.contains("\"kind\":\"job\"")).toList();
        assertThat(jobs).as(out.toString()).hasSize(3);
        assertThat(jobs).filteredOn(l -> l.contains("ViewRefreshJob")).singleElement().asString()
                .contains("\"cron\":\"0 45 1 ? * 2\"", "\"position\":\"1 of 2\"", "\"region-code\":\"N\"");
        assertThat(jobs).filteredOn(l -> l.contains("WeeklyTotalsJob")).singleElement().asString()
                .contains("\"cron\":\"0 45 1 ? * 2\"", "\"cron\":\"0 15 2 ? * 2\"", "\"runsAfter\":\"VIEW_REFRESH\"",
                        "\"name\":\"MondayRunSouth\"", "\"description\":\"South jobs for last week\"");
        assertThat(jobs).filteredOn(l -> l.contains("DailyTotalsJob")).singleElement().asString().doesNotContain("cron");
    }
}
