package dev.logast.trace;

import dev.logast.Fixtures;
import dev.logast.Logast;
import dev.logast.export.Export;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #217: a scheduler set up by following the code, not by key names: triggers bound from the configuration (a class per
 * key extending a common one), the first job of each from a params object that polls a chain, the job's class from an
 * enum constant through a provider's EnumMap of JobDetails, and the rest of the chain from a global JobListener that
 * polls the params again after each job and triggers the next. Lombok and Quartz are not on the classpath.
 */
class TracedSchedulesTest {
    @TempDir
    Path tmp;

    private static void write(Path root, String cls, String code) throws IOException {
        Path f = root.resolve("src/main/java/com/example/batch/" + cls + ".java");
        Files.createDirectories(f.getParent());
        Files.writeString(f, code);
    }

    static Path service(Path tmp) throws IOException {
        Path root = tmp.resolve("batch");
        Files.createDirectories(root.resolve("src/main/resources"));
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { java }\n");
        Files.writeString(root.resolve("src/main/resources/application.yml"), """
                spring.application.name: batch
                batch:
                  triggers:
                    weekly-a:
                      name: weekly-a
                      group: batch
                      cron: "0 0 1 ? * MON"
                      plans:
                        - description: first run
                          country: GB
                          chain:
                            - name: LOAD
                            - name: TOTALS
                              delay-in-minutes: 5
                        - description: second run
                          country: IE
                          chain:
                            - name: REPORT
                    daily-b:
                      name: daily-b
                      cron: "0 30 2 * * ?"
                      plans:
                        - description: daily
                          country: GB
                          chain:
                            - name: totals
                """);
        for (String job : List.of("LoadJob", "TotalsJob", "ReportJob")) {
            write(root, job, """
                    package com.example.batch;

                    import org.quartz.Job;
                    import org.quartz.JobExecutionContext;

                    public class %s implements Job {
                        @Override
                        public void execute(JobExecutionContext context) {
                        }
                    }
                    """.formatted(job));
        }
        write(root, "JobType", """
                package com.example.batch;

                import lombok.Getter;
                import lombok.RequiredArgsConstructor;
                import org.quartz.Job;

                @Getter
                @RequiredArgsConstructor
                public enum JobType {
                    LOAD("load", LoadJob.class, "Loads the data"),
                    TOTALS("totals", TotalsJob.class, "Adds it up"),
                    REPORT("report", ReportJob.class, "Reports it");

                    private final String key;
                    private final Class<? extends Job> jobClass;
                    private final String description;
                }
                """);
        write(root, "JobKeys", """
                package com.example.batch;

                public final class JobKeys {
                    public static final String PARAMS = "batch.params";
                }
                """);
        write(root, "BatchTrigger", """
                package com.example.batch;

                import lombok.Getter;
                import lombok.Setter;
                import java.util.List;

                @Getter
                @Setter
                public class BatchTrigger {
                    private String name;
                    private String group;
                    private String cron;
                    private List<JobParams.Plan> plans;
                }
                """);
        write(root, "JobParams", """
                package com.example.batch;

                import lombok.Builder;
                import lombok.Getter;
                import lombok.Value;
                import java.util.LinkedList;
                import java.util.List;
                import java.util.Optional;

                @Getter
                @Builder
                public class JobParams {
                    private Current current;
                    private List<Plan> plans;

                    public Optional<JobParams> next() {
                        return initialize(plans);
                    }

                    public static Optional<JobParams> initialize(List<Plan> plans) {
                        return pollNext(plans).map(c -> builder().current(c).plans(plans).build());
                    }

                    private static Optional<Current> pollNext(List<Plan> plans) {
                        if (plans.isEmpty()) {
                            return Optional.empty();
                        }
                        var plan = plans.getFirst();
                        var result = plan.pollNext();
                        if (plan.isCompleted()) {
                            plans.removeFirst();
                        }
                        return result;
                    }

                    @Value
                    @Builder
                    public static class Current {
                        JobType name;
                        String country;
                        @Builder.Default
                        Integer delayInMinutes = 0;
                    }

                    @Value
                    @Builder
                    public static class Plan {
                        String description;
                        String country;
                        LinkedList<Link> chain;

                        public boolean isCompleted() {
                            return chain.isEmpty();
                        }

                        public Optional<Current> pollNext() {
                            return Optional.ofNullable(chain.pollFirst())
                                    .map(l -> Current.builder().name(l.getName()).country(country).delayInMinutes(l.getDelayInMinutes()).build());
                        }
                    }

                    @Value
                    public static class Link {
                        JobType name;
                        Integer delayInMinutes;
                    }
                }
                """);
        write(root, "JobsConfig", """
                package com.example.batch;

                import static com.example.batch.JobType.*;

                import lombok.AllArgsConstructor;
                import org.quartz.JobDetail;
                import org.springframework.boot.context.properties.ConfigurationProperties;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.scheduling.quartz.JobDetailFactoryBean;
                import java.util.EnumMap;
                import java.util.HashMap;
                import java.util.Map;
                import java.util.Optional;

                @Configuration
                public class JobsConfig {
                    @Bean(name = "loadJobDetail")
                    JobDetailFactoryBean loadJobDetail() {
                        return detail(LOAD);
                    }

                    @Bean(name = "totalsJobDetail")
                    JobDetailFactoryBean totalsJobDetail() {
                        return detail(TOTALS);
                    }

                    @Bean(name = "reportJobDetail")
                    JobDetailFactoryBean reportJobDetail() {
                        return detail(REPORT);
                    }

                    private JobDetailFactoryBean detail(JobType type) {
                        JobDetailFactoryBean f = new JobDetailFactoryBean();
                        f.setJobClass(type.getJobClass());
                        f.setName(type.getKey());
                        f.setDescription(type.getDescription());
                        f.setDurability(true);
                        return f;
                    }

                    @Bean
                    JobProvider jobProvider() {
                        Map<JobType, JobDetail> details = new HashMap<>();
                        details.put(LOAD, loadJobDetail().getObject());
                        details.put(TOTALS, totalsJobDetail().getObject());
                        details.put(REPORT, reportJobDetail().getObject());
                        return new JobProvider(new EnumMap<>(details));
                    }

                    @AllArgsConstructor
                    public static class JobProvider {
                        private final EnumMap<JobType, JobDetail> jobs;

                        public Optional<JobDetail> getJob(JobType type) {
                            return Optional.ofNullable(jobs.get(type));
                        }
                    }

                    @Configuration
                    @ConfigurationProperties(prefix = "batch.triggers.weekly-a")
                    public static class WeeklyATrigger extends BatchTrigger {
                    }

                    @Configuration
                    @ConfigurationProperties(prefix = "batch.triggers.daily-b")
                    public static class DailyBTrigger extends BatchTrigger {
                    }
                }
                """);
        write(root, "TriggersConfig", """
                package com.example.batch;

                import jakarta.annotation.PostConstruct;
                import lombok.RequiredArgsConstructor;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.scheduling.quartz.CronTriggerFactoryBean;
                import java.util.List;
                import java.util.TimeZone;

                @Configuration
                @RequiredArgsConstructor
                public class TriggersConfig {
                    private static final TimeZone ZONE = TimeZone.getTimeZone("GMT");

                    private final List<BatchTrigger> triggers;
                    private final JobsConfig.JobProvider jobProvider;

                    @PostConstruct
                    public void register() {
                        triggers.stream().map(this::cronTrigger).forEach(t -> {
                        });
                    }

                    private CronTriggerFactoryBean cronTrigger(BatchTrigger trigger) {
                        CronTriggerFactoryBean f = new CronTriggerFactoryBean();
                        JobParams params = JobParams.initialize(trigger.getPlans())
                                .orElseThrow(() -> new IllegalStateException("no plan"));
                        f.setJobDetail(jobProvider.getJob(params.getCurrent().getName())
                                .orElseThrow(() -> new IllegalStateException("no job")));
                        f.setGroup(trigger.getGroup());
                        f.setName(trigger.getName());
                        f.setCronExpression(trigger.getCron());
                        f.setTimeZone(ZONE);
                        f.getJobDataMap().put(JobKeys.PARAMS, params);
                        return f;
                    }
                }
                """);
        write(root, "ChainListener", """
                package com.example.batch;

                import lombok.RequiredArgsConstructor;
                import org.quartz.JobDataMap;
                import org.quartz.JobDetail;
                import org.quartz.JobExecutionContext;
                import org.quartz.JobExecutionException;
                import org.quartz.JobKey;
                import org.quartz.JobListener;
                import org.quartz.SchedulerException;
                import org.quartz.Trigger;
                import org.quartz.TriggerBuilder;
                import java.util.Objects;
                import java.util.Optional;

                @RequiredArgsConstructor
                public class ChainListener implements JobListener {
                    private final String name;
                    private final JobsConfig.JobProvider jobProvider;

                    @Override
                    public String getName() {
                        return name;
                    }

                    @Override
                    public void jobToBeExecuted(JobExecutionContext context) {
                    }

                    @Override
                    public void jobExecutionVetoed(JobExecutionContext context) {
                    }

                    @Override
                    public void jobWasExecuted(JobExecutionContext context, JobExecutionException failure) {
                        if (Objects.isNull(failure) && Objects.nonNull(context.getTrigger().getJobDataMap())) {
                            Optional<Object> param = Optional.ofNullable(context.getTrigger().getJobDataMap().get(JobKeys.PARAMS));
                            param.flatMap(o -> ((JobParams) o).next()).ifPresent(next -> scheduleNext(context, next));
                        }
                    }

                    private void scheduleNext(JobExecutionContext context, JobParams next) {
                        var job = next.getCurrent().getName();
                        var delay = next.getCurrent().getDelayInMinutes();
                        try {
                            JobDataMap data = new JobDataMap();
                            data.put(JobKeys.PARAMS, next);
                            if (delay.equals(0)) {
                                context.getScheduler().triggerJob(key(job), data);
                            } else {
                                Trigger t = TriggerBuilder.newTrigger().usingJobData(data).forJob(key(job)).build();
                                context.getScheduler().scheduleJob(t);
                            }
                        } catch (SchedulerException e) {
                            throw new IllegalStateException(e);
                        }
                    }

                    private JobKey key(JobType type) {
                        return jobProvider.getJob(type).map(JobDetail::getKey).orElseThrow();
                    }
                }
                """);
        write(root, "QuartzConfig", """
                package com.example.batch;

                import lombok.RequiredArgsConstructor;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.scheduling.quartz.SchedulerFactoryBean;

                @Configuration
                @RequiredArgsConstructor
                public class QuartzConfig {
                    private final JobsConfig.JobProvider jobProvider;

                    @Bean
                    SchedulerFactoryBean scheduler() {
                        SchedulerFactoryBean f = new SchedulerFactoryBean();
                        f.setGlobalJobListeners(new ChainListener("chain", jobProvider));
                        return f;
                    }
                }
                """);
        return root;
    }

    @Test
    void triggersJobsAndChainsAreFollowedThroughTheCode() throws IOException {
        Path root = service(tmp);
        StringWriter out = new StringWriter();
        Export.java(root, Fixtures.classpath(), Logast.policy(root, null), "batch", "test", out);
        String facts = out.toString();
        List<String> jobs = facts.lines().filter(l -> l.contains("\"kind\":\"job\"")).toList();
        String load = jobs.stream().filter(l -> l.contains("job:batch:com.example.batch.LoadJob\"")).findFirst().orElseThrow();
        String totals = jobs.stream().filter(l -> l.contains("job:batch:com.example.batch.TotalsJob\"")).findFirst().orElseThrow();
        String report = jobs.stream().filter(l -> l.contains("job:batch:com.example.batch.ReportJob\"")).findFirst().orElseThrow();
        assertThat(load).as(facts).contains("\"cron\":\"0 0 1 ? * MON\"", "\"cronFrom\":\"batch.triggers.weekly-a.cron\"",
                "\"zone\":\"GMT\"", "\"group\":\"batch\"", "\"chain\":\"weekly-a\"", "\"position\":\"1 of 3\"", "\"country\":\"GB\"");
        assertThat(totals).as("in the chain after the first, and on its own trigger; its delay from the configuration")
                .contains("\"position\":\"2 of 3\"", "\"runsAfter\":\"LoadJob\"", "\"delayInMinutes\":\"5\"",
                        "\"name\":\"daily-b\"", "\"cron\":\"0 30 2 * * ?\"");
        assertThat(report).as("the next plan's chain, after the first plan's").contains("\"position\":\"3 of 3\"",
                "\"runsAfter\":\"TotalsJob\"", "\"country\":\"IE\"");
    }

    /** #217: the configuration's values are facts, a profile's where it differs; a secret never is. */
    @Test
    void configurationValuesAreFacts() throws IOException {
        Path root = service(tmp);
        Path res = root.resolve("src/main/resources");
        Files.writeString(res.resolve("application.yml"), Files.readString(res.resolve("application.yml"))
                + "spring.datasource.password: s3cr3t-value\n");
        Files.writeString(res.resolve("application-prod.yml"), "batch.triggers.daily-b.cron: \"0 0 4 * * ?\"\n");
        StringWriter out = new StringWriter();
        Export.java(root, Fixtures.classpath(), Logast.policy(root, null), "batch", "test", out);
        String facts = out.toString();
        assertThat(facts.lines().filter(l -> l.contains("\"id\":\"property:batch:batch.triggers.daily-b.cron\"")))
                .singleElement().asString().contains("\"value\":\"0 30 2 * * ?\"", "\"byProfile\":{\"prod\":\"0 0 4 * * ?\"}",
                        "\"file\":\"src/main/resources/application.yml\"");
        assertThat(facts).contains("\"id\":\"property:batch:batch.triggers.weekly-a.plans[0].chain[1].delay-in-minutes\"")
                .contains("\"id\":\"property:batch:spring.datasource.password\"").doesNotContain("s3cr3t-value");
    }

    /** #215: the code's own values: enum constants with what their constructor gives their fields, constants. */
    @Test
    void enumConstantsAndConstantsAreFacts() throws IOException {
        Path root = service(tmp);
        StringWriter out = new StringWriter();
        Export.java(root, Fixtures.classpath(), Logast.policy(root, null), "batch", "test", out);
        String facts = out.toString();
        assertThat(facts.lines().filter(l -> l.contains("\"id\":\"enum-constant:batch:JobType.LOAD\""))).singleElement().asString()
                .contains("\"jobClass\":\"com.example.batch.LoadJob\"", "\"key\":\"load\"", "\"description\":\"Loads the data\"");
        assertThat(facts.lines().filter(l -> l.contains("\"id\":\"constant:batch:JobKeys.PARAMS\""))).singleElement().asString()
                .contains("\"value\":\"batch.params\"");
    }

    /** #218: with LOGAST_TRACE, the tracer says what it ran, the triggers it built and what it could not know. */
    @Test
    void theTracerSaysWhatItDidWhenAsked() throws IOException {
        Path root = service(tmp);
        var err = System.err;
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        System.setProperty("logast.trace", "true");
        try {
            System.setErr(new java.io.PrintStream(buf, true, java.nio.charset.StandardCharsets.UTF_8));
            Export.java(root, Fixtures.classpath(), Logast.policy(root, null), "batch", "test", new StringWriter());
        } finally {
            System.setErr(err);
            System.clearProperty("logast.trace");
        }
        String said = buf.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(said).contains("trace: triggers built: 2", "trace: listeners: [ChainListener]",
                "trace:   run weekly-a: [LoadJob, TotalsJob, ReportJob]", "TriggersConfig.register", "trace: not known, most often:");
    }
}
