package dev.logast.rules;

import dev.logast.Analysis;
import dev.logast.Fixtures;
import dev.logast.Traffic;
import dev.logast.orders.WorkOrder;
import dev.logast.policy.Policy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #58 c, followability: a report-only finding tells the reader what to change. Doing exactly that must make the
 * finding go away, or the remedy and the rule disagree and the finding can never be closed. Each case is a small
 * project that fires the rule, and the same project with the note's remedy applied, which must not.
 *
 * <p>A remedy logast does not recognise yet is a {@link #stillFires known gap}: the test asserts that it still fires
 * and names the issue, so it fails (and must be turned into a plain remedy) once the rule learns it.
 */
class RemedyTest {
    private static final String PACKS = "packs: [logging, tracing, metrics, resilience, errors, audit]\n";

    @TempDir
    Path dir;
    private int projects;

    /**
     * Writes a project (a logast.yaml with every pack unless the files bring their own, and its .logast/traffic.yaml if
     * the files bring one) and returns its orders.
     */
    private List<WorkOrder> scan(Map<String, String> files) throws IOException {
        return scan(files, List.of());
    }

    private List<WorkOrder> scan(Map<String, String> files, List<Path> extraClasspath) throws IOException {
        Path root = Files.createDirectories(dir.resolve("p" + projects++));
        Map<String, String> all = new LinkedHashMap<>(Map.of("logast.yaml", PACKS,
                "src/main/resources/application.yml", "spring:\n  application:\n    name: remedy\n"));
        all.putAll(files);
        for (Map.Entry<String, String> f : all.entrySet()) {
            Path p = root.resolve(f.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, f.getValue());
        }
        Policy policy = Policy.resolve(root, null);
        List<Path> classpath = new java.util.ArrayList<>(Fixtures.classpath());
        classpath.addAll(extraClasspath);
        return Rules.scan(Analysis.run(root, classpath, policy).withTraffic(Traffic.load(root, null))).orders();
    }

    /** Compiles stand-ins for a library missing from the fixture's classpath into a jar (logast ignores class folders). */
    private Path stubJar(Map<String, String> sources) throws IOException {
        Path src = dir.resolve("stubs/src");
        Path out = Files.createDirectories(dir.resolve("stubs/classes"));
        List<String> args = new java.util.ArrayList<>(List.of("-d", out.toString(), "-cp",
                String.join(java.io.File.pathSeparator, Fixtures.classpath().stream().map(Path::toString).toList())));
        for (Map.Entry<String, String> e : sources.entrySet()) {
            Path f = src.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
            args.add(f.toString());
        }
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        assertThat(javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, err, args.toArray(String[]::new)))
                .as("stubs compile:\n" + err).isZero();
        Path jar = dir.resolve("stubs/stubs.jar");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar));
             java.util.stream.Stream<Path> s = Files.walk(out)) {
            for (Path c : s.filter(Files::isRegularFile).sorted().toList()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(out.relativize(c).toString().replace('\\', '/')));
                zip.write(Files.readAllBytes(c));
                zip.closeEntry();
            }
        }
        return jar;
    }

    /** The rules a project reports. */
    private Set<String> fired(Map<String, String> files) throws IOException {
        return RuleLedgerTest.mentioned(scan(files));
    }

    private static Map<String, String> with(Map<String, String> base, String... pathsAndTexts) {
        Map<String, String> out = new LinkedHashMap<>(base);
        for (int i = 0; i < pathsAndTexts.length; i += 2) {
            if (pathsAndTexts[i + 1] == null) {
                out.remove(pathsAndTexts[i]);   // the file left out
            } else {
                out.put(pathsAndTexts[i], pathsAndTexts[i + 1]);
            }
        }
        return out;
    }

    private void fires(String rule, Map<String, String> before) throws IOException {
        assertThat(fired(before)).as(rule + " fires before the remedy").contains(rule);
    }

    private void gone(String rule, String remedy, Map<String, String> after) throws IOException {
        assertThat(fired(after)).as(rule + " is gone after the remedy: " + remedy).doesNotContain(rule);
    }

    /** A remedy the note names that the rule does not recognise yet (issue in {@code why}). */
    private void stillFires(String rule, String remedy, String why, Map<String, String> after) throws IOException {
        assertThat(why).containsPattern("#\\d+");
        assertThat(fired(after)).as(rule + " still fires after the remedy (" + remedy + "): " + why
                + ". If the rule recognises it now, make this a plain remedy").contains(rule);
    }

    /**
     * A remedy after which logast makes the change itself (the done-when line says so): the report-only finding becomes
     * an AUTO order of the same rule.
     */
    private void becomesAuto(String rule, String remedy, Map<String, String> after) throws IOException {
        List<WorkOrder> orders = scan(after).stream().filter(o -> o.rule().equals(rule)).toList();
        assertThat(orders).as(rule + " is no longer report-only after the remedy: " + remedy).isNotEmpty()
                .noneMatch(WorkOrder::reportOnly);
    }

    private static String java(String name) {
        return "src/main/java/x/" + name + ".java";
    }

    // ---- KAFKA_NO_ERROR_HANDLER ------------------------------------------------------------------------------------

    @Test
    void kafkaListenerWithACommonErrorHandlerBean() throws IOException {
        Map<String, String> before = Map.of(java("AckListener"), """
                package x;

                import org.springframework.kafka.annotation.KafkaListener;
                import org.springframework.stereotype.Component;

                @Component
                public class AckListener {
                    @KafkaListener(topics = "acks")
                    public void onAck(String payload) {
                        System.out.println(payload);
                    }
                }
                """);
        fires(Rules.KAFKA_NO_ERROR_HANDLER, before);
        gone(Rules.KAFKA_NO_ERROR_HANDLER, "a CommonErrorHandler bean with a dead-letter recoverer", with(before, java("KafkaErrors"), """
                package x;

                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.kafka.core.KafkaTemplate;
                import org.springframework.kafka.listener.CommonErrorHandler;
                import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
                import org.springframework.kafka.listener.DefaultErrorHandler;
                import org.springframework.util.backoff.ExponentialBackOff;

                @Configuration
                public class KafkaErrors {
                    @Bean
                    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> template) {
                        return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), new ExponentialBackOff(1000, 2));
                    }
                }
                """));
    }

    // ---- RABBIT_NO_ERROR_HANDLER -----------------------------------------------------------------------------------

    private static final String RETURN_LISTENER = """
            package x;

            import org.springframework.amqp.rabbit.annotation.RabbitListener;
            import org.springframework.stereotype.Component;

            @Component
            public class ReturnListener {
                @RabbitListener(queues = "returns"%s)
                public void onReturn(String payload) {
                    %s
                }
            }
            """;

    @Test
    void rabbitListenerRetriedOrRejectedForGood() throws IOException {
        Map<String, String> before = Map.of(java("ReturnListener"), RETURN_LISTENER.formatted("", "System.out.println(payload);"));
        fires(Rules.RABBIT_NO_ERROR_HANDLER, before);
        gone(Rules.RABBIT_NO_ERROR_HANDLER, "listener retries", with(before, YML,
                CONFIG + "  rabbitmq:\n    listener:\n      simple:\n        retry:\n          enabled: true\n"));
        gone(Rules.RABBIT_NO_ERROR_HANDLER, "default-requeue-rejected: false", with(before, YML,
                CONFIG + "  rabbitmq:\n    listener:\n      simple:\n        default-requeue-rejected: false\n"));
        gone(Rules.RABBIT_NO_ERROR_HANDLER, "retries of the direct container", with(before, YML,
                CONFIG + "  rabbitmq:\n    listener:\n      type: direct\n      direct:\n        retry:\n          enabled: true\n"));
        gone(Rules.RABBIT_NO_ERROR_HANDLER, "a container factory that does not requeue", with(before, java("RabbitConfig"), """
                package x;

                import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class RabbitConfig {
                    @Bean
                    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory() {
                        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
                        factory.setDefaultRequeueRejected(false);
                        return factory;
                    }
                }
                """));
        gone(Rules.RABBIT_NO_ERROR_HANDLER, "AmqpRejectAndDontRequeueException", with(before, java("ReturnListener"),
                RETURN_LISTENER.formatted("", "throw new org.springframework.amqp.AmqpRejectAndDontRequeueException(payload);")));
        gone(Rules.RABBIT_NO_ERROR_HANDLER, "manual acknowledgement", with(before, java("ReturnListener"),
                RETURN_LISTENER.formatted(", ackMode = \"MANUAL\"", "System.out.println(payload);")));
        // retries switched on for the other container type do not reach this one
        fires(Rules.RABBIT_NO_ERROR_HANDLER, with(before, YML,
                CONFIG + "  rabbitmq:\n    listener:\n      direct:\n        retry:\n          enabled: true\n"));
    }

    // ---- RETRY_IN_TRANSACTION --------------------------------------------------------------------------------------

    private static final String SEATS = """
            package x;

            import org.springframework.resilience.annotation.Retryable;
            import org.springframework.stereotype.Component;
            import org.springframework.transaction.annotation.Propagation;
            import org.springframework.transaction.annotation.Transactional;

            @Component
            public class Seats {
                %s
                public int hold(String seatId) {
                    return 1;
                }
            }
            """;
    private static final String BOOKING = """
            package x;

            import org.springframework.resilience.annotation.Retryable;
            import org.springframework.stereotype.Service;
            import org.springframework.transaction.annotation.Transactional;

            @Service
            public class Booking {
                private final Seats seats;

                public Booking(Seats seats) {
                    this.seats = seats;
                }

                %s
                public int book(String seatId) {
                    return seats.hold(seatId);
                }
            }
            """;

    @Test
    void retryWrapsTheWholeTransaction() throws IOException {
        Map<String, String> before = Map.of(java("Seats"), SEATS.formatted("@Retryable @Transactional"),
                java("Booking"), BOOKING.formatted("@Transactional"));
        fires(Rules.RETRY_IN_TRANSACTION, before);
        gone(Rules.RETRY_IN_TRANSACTION, "a transaction of its own per attempt", with(before, java("Seats"),
                SEATS.formatted("@Retryable @Transactional(propagation = Propagation.REQUIRES_NEW)")));
        gone(Rules.RETRY_IN_TRANSACTION, "the retry moved to the method that starts the transaction", with(before,
                java("Seats"), SEATS.formatted("@Transactional"), java("Booking"), BOOKING.formatted("@Retryable @Transactional")));
        gone(Rules.RETRY_IN_TRANSACTION, "no outer transaction", with(before, java("Booking"), BOOKING.formatted("")));
    }

    // ---- PROBLEM_DETAILS_DISABLED ----------------------------------------------------------------------------------

    @Test
    void problemDetailsByPropertyOrByAdvice() throws IOException {
        Map<String, String> before = Map.of(java("ParcelController"), """
                package x;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class ParcelController {
                    @GetMapping("/parcels/{parcelId}")
                    public String parcel(@PathVariable String parcelId) {
                        return parcelId;
                    }
                }
                """);
        fires(Rules.PROBLEM_DETAILS_DISABLED, before);
        gone(Rules.PROBLEM_DETAILS_DISABLED, "spring.mvc.problemdetails.enabled: true", with(before,
                "src/main/resources/application.yml", """
                        spring:
                          application:
                            name: remedy
                          mvc:
                            problemdetails:
                              enabled: true
                        """));
        gone(Rules.PROBLEM_DETAILS_DISABLED, "an advice extending ResponseEntityExceptionHandler", with(before, java("ApiErrors"), """
                package x;

                import org.springframework.web.bind.annotation.RestControllerAdvice;
                import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

                @RestControllerAdvice
                public class ApiErrors extends ResponseEntityExceptionHandler {
                }
                """));
    }

    // ---- SCHEDULED_WITHOUT_LOCK ------------------------------------------------------------------------------------

    @Test
    void scheduledJobWithALockOrOnASingleInstance() throws IOException {
        String job = """
                package x;

                %simport org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;

                @Component
                public class SweepJob {
                    @Scheduled(fixedDelay = 60000)
                    %spublic void sweep() {
                        System.out.println("sweep");
                    }
                }
                """;
        Map<String, String> before = Map.of(java("SweepJob"), job.formatted("", ""));
        fires(Rules.SCHEDULED_WITHOUT_LOCK, before);
        // ShedLock is not on the fixture's classpath: the annotation stays unresolved and is matched by its name
        gone(Rules.SCHEDULED_WITHOUT_LOCK, "@SchedulerLock", Map.of(java("SweepJob"), job.formatted(
                "import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;\n",
                "@SchedulerLock(name = \"sweep\")\n    ")));
        gone(Rules.SCHEDULED_WITHOUT_LOCK, "resilience.singleInstance: true", with(before,
                "logast.yaml", PACKS + "resilience:\n  singleInstance: true\n"));
    }

    // ---- DATA_CHANGES_UNAUDITED ------------------------------------------------------------------------------------

    @Test
    void entityWithLastModifiedByAndAnAuditor() throws IOException {
        String parcel = """
                package x;

                import org.springframework.data.annotation.CreatedBy;
                import org.springframework.data.annotation.Id;
                import org.springframework.data.annotation.LastModifiedBy;

                public class Parcel {
                    @Id
                    private String id;
                    private String status;
                %s}
                """;
        Map<String, String> before = Map.of(java("Parcel"), parcel.formatted(""), java("ParcelRepository"), """
                package x;

                import org.springframework.data.repository.CrudRepository;

                public interface ParcelRepository extends CrudRepository<Parcel, String> {
                }
                """);
        fires(Rules.DATA_CHANGES_UNAUDITED, before);
        Map<String, String> audited = with(before, java("Parcel"), parcel.formatted("""
                    @CreatedBy
                    private String createdBy;
                    @LastModifiedBy
                    private String lastModifiedBy;
                """));
        // half the remedy: the fields alone stay empty without an AuditorAware, which is reported in turn
        fires(Rules.DATA_CHANGES_UNAUDITED, audited);
        gone(Rules.DATA_CHANGES_UNAUDITED, "@CreatedBy / @LastModifiedBy and an AuditorAware bean", with(audited, java("Auditing"), """
                package x;

                import java.util.Optional;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.data.domain.AuditorAware;

                @Configuration
                public class Auditing {
                    @Bean
                    AuditorAware<String> auditor() {
                        return () -> Optional.of("system");
                    }
                }
                """));
    }

    /** #92: a bulk statement on an audited entity's table bypasses auditing, unless it sets the editor column itself. */
    @Test
    void bulkUpdateSetsTheEditor() throws IOException {
        String writer = """
                package x;

                import org.springframework.jdbc.core.JdbcTemplate;

                public class ParcelWriter {
                    private final JdbcTemplate jdbc;

                    public ParcelWriter(JdbcTemplate jdbc) {
                        this.jdbc = jdbc;
                    }

                    public int hold(String parcelId, String by) {
                        return jdbc.update(%s, parcelId);
                    }
                }
                """;
        Map<String, String> audited = Map.of(java("Parcel"), """
                package x;

                import org.springframework.data.annotation.Id;
                import org.springframework.data.annotation.LastModifiedBy;

                public class Parcel {
                    @Id
                    private String id;
                    @LastModifiedBy
                    private String lastModifiedBy;
                }
                """, java("ParcelRepository"), """
                package x;

                import org.springframework.data.repository.CrudRepository;

                public interface ParcelRepository extends CrudRepository<Parcel, String> {
                }
                """, java("Auditing"), """
                package x;

                import java.util.Optional;
                import org.springframework.context.annotation.Bean;
                import org.springframework.data.domain.AuditorAware;

                public class Auditing {
                    @Bean
                    AuditorAware<String> auditor() {
                        return () -> Optional.of("system");
                    }
                }
                """);
        fires(Rules.DATA_CHANGES_UNAUDITED, with(audited, java("ParcelWriter"),
                writer.formatted("\"update parcel set status = 'HELD' where id = ?\"")));
        gone(Rules.DATA_CHANGES_UNAUDITED, "the statement sets the @LastModifiedBy column", with(audited, java("ParcelWriter"),
                writer.formatted("\"update parcel set status = 'HELD', last_modified_by = ? where id = ?\", by")));
    }

    // ---- AUDIT_SECURITY_EVENTS -------------------------------------------------------------------------------------

    /** Stand-ins for Spring Security and Actuator's audit types, which the fixture's classpath does not have. */
    private static final Map<String, String> SECURITY_STUBS = Map.of(
            "org/springframework/security/authentication/event/AbstractAuthenticationEvent.java", """
                    package org.springframework.security.authentication.event;

                    public abstract class AbstractAuthenticationEvent extends org.springframework.context.ApplicationEvent {
                        protected AbstractAuthenticationEvent(Object source) {
                            super(source);
                        }
                    }
                    """,
            "org/springframework/security/authorization/event/AuthorizationEvent.java", """
                    package org.springframework.security.authorization.event;

                    public class AuthorizationEvent extends org.springframework.context.ApplicationEvent {
                        public AuthorizationEvent(Object source) {
                            super(source);
                        }
                    }
                    """,
            "org/springframework/security/authorization/AuthorizationEventPublisher.java", """
                    package org.springframework.security.authorization;

                    public interface AuthorizationEventPublisher {
                    }
                    """,
            "org/springframework/boot/actuate/audit/AuditEventRepository.java", """
                    package org.springframework.boot.actuate.audit;

                    public interface AuditEventRepository {
                    }
                    """,
            "org/springframework/boot/actuate/audit/InMemoryAuditEventRepository.java", """
                    package org.springframework.boot.actuate.audit;

                    public class InMemoryAuditEventRepository implements AuditEventRepository {
                    }
                    """);

    private Set<String> securityTitles(List<Path> cp, String... pathsAndTexts) throws IOException {
        return scan(with(Map.of(), pathsAndTexts), cp).stream().filter(o -> o.rule().equals(Rules.AUDIT_SECURITY_EVENTS))
                .map(WorkOrder::title).collect(java.util.stream.Collectors.toSet());
    }

    /**
     * #92: security events recorded nowhere durable. Nothing at all fires; Boot's in-memory repository is reported
     * (Boot recommends it only for development), under a dev profile it does not count; a repository of the service's
     * own is the remedy. A listener for authorization events needs an AuthorizationEventPublisher bean.
     */
    @Test
    void securityEventsRecordedDurably() throws IOException {
        List<Path> cp = List.of(stubJar(SECURITY_STUBS));
        String config = """
                package x;

                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.context.annotation.Profile;
                import org.springframework.boot.actuate.audit.InMemoryAuditEventRepository;

                @Configuration
                public class AuditConfig {
                    @Bean
                    %s
                    InMemoryAuditEventRepository auditEvents() {
                        return new InMemoryAuditEventRepository();
                    }
                }
                """;
        assertThat(securityTitles(cp, java("Plain"), "package x;\n\npublic class Plain {\n}\n"))
                .singleElement().asString().contains("nothing records failed logins");
        assertThat(securityTitles(cp, java("AuditConfig"), config.formatted("")))
                .singleElement().asString().contains("recorded in an InMemoryAuditEventRepository (@Bean AuditConfig.auditEvents)");
        assertThat(securityTitles(cp, java("AuditConfig"), config.formatted("@Profile(\"dev\")")))
                .as("under a dev profile it is no production record, and not reported as one")
                .singleElement().asString().contains("nothing records failed logins");
        assertThat(securityTitles(cp, java("AuditConfig"), config.formatted(""), java("DurableAuditEvents"), """
                package x;

                import org.springframework.boot.actuate.audit.AuditEventRepository;

                public class DurableAuditEvents implements AuditEventRepository {
                }
                """)).as("a durable repository of its own").isEmpty();

        String listener = """
                package x;

                import org.springframework.context.event.EventListener;
                import org.springframework.security.authentication.event.AbstractAuthenticationEvent;
                import org.springframework.security.authorization.event.AuthorizationEvent;

                public class SecurityAudit {
                    @EventListener
                    public void onLogin(AbstractAuthenticationEvent event) {
                    }

                    @EventListener
                    public void onAccess(AuthorizationEvent event) {
                    }
                }
                """;
        assertThat(securityTitles(cp, java("SecurityAudit"), listener)).singleElement().asString()
                .contains("SecurityAudit.onAccess listens for authorization events, but no AuthorizationEventPublisher bean");
        assertThat(securityTitles(cp, java("SecurityAudit"), listener, java("Publishing"), """
                package x;

                import org.springframework.context.annotation.Bean;
                import org.springframework.security.authorization.AuthorizationEventPublisher;

                public class Publishing {
                    @Bean
                    AuthorizationEventPublisher authorizationEventPublisher() {
                        return new AuthorizationEventPublisher() {
                        };
                    }
                }
                """)).as("with an AuthorizationEventPublisher bean").isEmpty();
    }

    // ---- UNBOUNDED_EXECUTOR ----------------------------------------------------------------------------------------

    @Test
    void springExecutorWithAQueueCapacity() throws IOException {
        String config = """
                package x;

                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

                @Configuration
                public class Executors {
                    @Bean
                    ThreadPoolTaskExecutor exportExecutor() {
                        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
                        executor.setMaxPoolSize(4);
                %s        return executor;
                    }
                }
                """;
        fires(Rules.UNBOUNDED_EXECUTOR, Map.of(java("Executors"), config.formatted("")));
        gone(Rules.UNBOUNDED_EXECUTOR, "setQueueCapacity(..)", Map.of(java("Executors"),
                config.formatted("        executor.setQueueCapacity(100);\n")));
    }

    // ---- UNBOUNDED_EXECUTOR (Spring Boot's executor behind @Async) and SCHEDULER_SINGLE_THREAD (#90) -----------------

    private static final String BOOT_APP = """
            package x;

            import org.springframework.boot.autoconfigure.SpringBootApplication;
            import org.springframework.scheduling.annotation.EnableAsync;
            import org.springframework.scheduling.annotation.EnableScheduling;

            @SpringBootApplication
            @EnableAsync
            @EnableScheduling
            public class ParcelApp {
            }
            """;

    private static final String PARCEL_JOBS = """
            package x;

            import org.springframework.scheduling.annotation.Async;
            import org.springframework.scheduling.annotation.Scheduled;
            import org.springframework.stereotype.Component;

            @Component
            public class ParcelJobs {
                @Async
                public void label(String parcelId) {
                    System.out.println(parcelId);
                }

                @Scheduled(fixedDelay = 60000)
                public void sweep() {
                    System.out.println("sweep");
                }

                @Scheduled(cron = "0 0 * * * *")
                public void archive() {
                    System.out.println("archive");
                }
            }
            """;

    private static final String CONFIG = "spring:\n  application:\n    name: remedy\n";

    @Test
    void asyncOnBootsExecutorWithABoundedQueue() throws IOException {
        Map<String, String> before = Map.of(java("ParcelApp"), BOOT_APP, java("ParcelJobs"), PARCEL_JOBS);
        fires(Rules.UNBOUNDED_EXECUTOR, before);
        gone(Rules.UNBOUNDED_EXECUTOR, "spring.task.execution.pool.queue-capacity", with(before, "src/main/resources/application.yml",
                CONFIG + "  task:\n    execution:\n      pool:\n        queue-capacity: 100\n        max-size: 16\n"));
        gone(Rules.UNBOUNDED_EXECUTOR, "virtual threads", with(before, "src/main/resources/application.yml",
                CONFIG + "  threads:\n    virtual:\n      enabled: true\n"));
        gone(Rules.UNBOUNDED_EXECUTOR, "an Executor bean of its own", with(before, java("AsyncConfig"), """
                package x;

                import java.util.concurrent.ArrayBlockingQueue;
                import java.util.concurrent.ExecutorService;
                import java.util.concurrent.ThreadPoolExecutor;
                import java.util.concurrent.TimeUnit;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class AsyncConfig {
                    @Bean
                    ExecutorService labelExecutor() {
                        return new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100));
                    }
                }
                """));
        // without @EnableAsync the annotation does nothing, and Boot's executor is not behind it
        gone(Rules.UNBOUNDED_EXECUTOR, "no @EnableAsync", with(before, java("ParcelApp"), BOOT_APP.replace("@EnableAsync\n", "")));
    }

    @Test
    void scheduledJobsWithAPoolOfTheirOwn() throws IOException {
        Map<String, String> before = Map.of(java("ParcelApp"), BOOT_APP, java("ParcelJobs"), PARCEL_JOBS);
        fires(Rules.SCHEDULER_SINGLE_THREAD, before);
        gone(Rules.SCHEDULER_SINGLE_THREAD, "spring.task.scheduling.pool.size", with(before, "src/main/resources/application.yml",
                CONFIG + "  task:\n    scheduling:\n      pool:\n        size: 2\n"));
        gone(Rules.SCHEDULER_SINGLE_THREAD, "virtual threads", with(before, "src/main/resources/application.yml",
                CONFIG + "  threads:\n    virtual:\n      enabled: true\n"));
        gone(Rules.SCHEDULER_SINGLE_THREAD, "a TaskScheduler bean of its own", with(before, java("SchedulingConfig"), """
                package x;

                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

                @Configuration
                public class SchedulingConfig {
                    @Bean
                    ThreadPoolTaskScheduler taskScheduler() {
                        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
                        scheduler.setPoolSize(4);
                        return scheduler;
                    }
                }
                """));
        // one job cannot delay another
        gone(Rules.SCHEDULER_SINGLE_THREAD, "a single job", with(before, java("ParcelJobs"),
                PARCEL_JOBS.replace("    @Scheduled(cron = \"0 0 * * * *\")\n", "")));
    }

    // ---- rollout and readiness (#29, #30, #90 item 2), unbounded caches (#90 item 7) ----------------------------------

    private static final String PARCEL_APP = """
            package x;

            import org.springframework.boot.autoconfigure.SpringBootApplication;

            @SpringBootApplication
            public class ParcelApp {
            }
            """;

    private static final String PARCEL_API = """
            package x;

            import javax.sql.DataSource;
            import org.springframework.jdbc.core.JdbcTemplate;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.PathVariable;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            public class ParcelApi {
                private final JdbcTemplate jdbc;

                public ParcelApi(JdbcTemplate jdbc) {
                    this.jdbc = jdbc;
                }

                @GetMapping("/parcels/{id}")
                public String status(@PathVariable String id) {
                    return jdbc.queryForObject("SELECT status FROM parcel WHERE id = ?", String.class, id);
                }
            }
            """;

    private static final String YML = "src/main/resources/application.yml";

    @Test
    void gracefulShutdownLongerThanTheWork() throws IOException {
        Map<String, String> before = Map.of(java("ParcelApp"), PARCEL_APP, java("ParcelApi"), PARCEL_API,
                YML, CONFIG + "server:\n  shutdown: immediate\n");
        fires(Rules.SHUTDOWN_NOT_GRACEFUL, before);
        gone(Rules.SHUTDOWN_NOT_GRACEFUL, "graceful is the default", with(before, YML, CONFIG));
        gone(Rules.SHUTDOWN_NOT_GRACEFUL, "server.shutdown: graceful", with(before, YML, CONFIG + "server:\n  shutdown: graceful\n"));
        gone(Rules.SHUTDOWN_NOT_GRACEFUL, "a library: no @SpringBootApplication", with(before, java("ParcelApp"), "package x;\n"));

        // the default phase (30s) is shorter than a transaction may run
        Map<String, String> longWork = with(before, YML, CONFIG + "  transaction:\n    default-timeout: 60\n");
        fires(Rules.SHUTDOWN_NOT_GRACEFUL, longWork);
        gone(Rules.SHUTDOWN_NOT_GRACEFUL, "a phase longer than the transaction timeout", with(longWork, YML,
                CONFIG + "  transaction:\n    default-timeout: 60\n  lifecycle:\n    timeout-per-shutdown-phase: 90s\n"));
        gone(Rules.SHUTDOWN_NOT_GRACEFUL, "a shorter transaction timeout", with(longWork, YML,
                CONFIG + "  transaction:\n    default-timeout: 20s\n"));
        fires(Rules.SHUTDOWN_NOT_GRACEFUL, with(before, YML, CONFIG + "  lifecycle:\n    timeout-per-shutdown-phase: 10s\n"
                + "  http:\n    clients:\n      connect-timeout: 2s\n      read-timeout: 15s\n"));
    }

    @Test
    void healthProbesOnAndLivenessKeptToTheInstance() throws IOException {
        Map<String, String> before = Map.of(java("ParcelApp"), PARCEL_APP, java("ParcelApi"), PARCEL_API, YML, CONFIG
                + "management:\n  endpoint:\n    health:\n      group:\n        liveness:\n          include: livenessState,db\n"
                + "        readiness:\n          include: readinessState,db\n");
        fires(Rules.HEALTH_PROBE_MISCONFIGURED, before);
        gone(Rules.HEALTH_PROBE_MISCONFIGURED, "liveness: livenessState only", with(before, YML, CONFIG
                + "management:\n  endpoint:\n    health:\n      group:\n        liveness:\n          include: livenessState\n"
                + "        readiness:\n          include: readinessState,db\n"));
        Map<String, String> off = with(before, YML, CONFIG
                + "management:\n  endpoint:\n    health:\n      probes:\n        enabled: false\n");
        fires(Rules.HEALTH_PROBE_MISCONFIGURED, off);
        gone(Rules.HEALTH_PROBE_MISCONFIGURED, "probes left on", with(before, YML, CONFIG
                + "management:\n  endpoint:\n    health:\n      probes:\n        enabled: true\n"));
        gone(Rules.HEALTH_PROBE_MISCONFIGURED, "a library: no @SpringBootApplication", with(off, java("ParcelApp"), "package x;\n"));
    }

    @Test
    void readinessWithTheDatabaseOrDecidedAgainst() throws IOException {
        Map<String, String> before = Map.of(java("ParcelApp"), PARCEL_APP, java("ParcelApi"), PARCEL_API, YML, CONFIG
                + "management:\n  endpoints:\n    web:\n      exposure:\n        include: health\n");
        fires(Rules.READINESS_WITHOUT_DATABASE, before);
        gone(Rules.READINESS_WITHOUT_DATABASE, "readiness includes db", with(before, YML, CONFIG
                + "management:\n  endpoint:\n    health:\n      group:\n        readiness:\n          include: readinessState,db\n"));
        gone(Rules.READINESS_WITHOUT_DATABASE, "decided against: the rule switched off", with(before, "logast.yaml",
                PACKS + "rules:\n  READINESS_WITHOUT_DATABASE:\n    enabled: false\n"));
        gone(Rules.READINESS_WITHOUT_DATABASE, "no Actuator", with(before, YML, CONFIG));
        gone(Rules.READINESS_WITHOUT_DATABASE, "a library: no @SpringBootApplication", with(before, java("ParcelApp"), "package x;\n"));
    }

    @Test
    void cachesWithASizeBound() throws IOException {
        Map<String, String> before = Map.of(java("ParcelApp"), PARCEL_APP.replace("@SpringBootApplication\n",
                        "@SpringBootApplication\n@org.springframework.cache.annotation.EnableCaching\n"),
                java("ParcelApi"), PARCEL_API);
        fires(Rules.CACHE_UNBOUNDED, before);
        gone(Rules.CACHE_UNBOUNDED, "Caffeine with maximumSize", with(before, YML,
                CONFIG + "  cache:\n    type: caffeine\n    caffeine:\n      spec: maximumSize=500,expireAfterWrite=10m\n"));
        fires(Rules.CACHE_UNBOUNDED, with(before, YML,
                CONFIG + "  cache:\n    type: caffeine\n    caffeine:\n      spec: expireAfterWrite=10m\n"));
        fires(Rules.CACHE_UNBOUNDED, with(before, YML, CONFIG + "  cache:\n    type: simple\n"));
        gone(Rules.CACHE_UNBOUNDED, "Redis", with(before, YML, CONFIG + "  cache:\n    type: redis\n"));
        gone(Rules.CACHE_UNBOUNDED, "a cache library in the build with a bounded spec", with(before,
                "build.gradle.kts", "dependencies {\n    implementation(\"com.github.ben-manes.caffeine:caffeine\")\n}\n",
                YML, CONFIG + "  cache:\n    caffeine:\n      spec: maximumSize=500\n"));
        gone(Rules.CACHE_UNBOUNDED, "a bounded CaffeineCacheManager bean", with(before, java("CacheConfig"), """
                package x;

                import org.springframework.cache.caffeine.CaffeineCacheManager;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class CacheConfig {
                    @Bean
                    CaffeineCacheManager cacheManager() {
                        CaffeineCacheManager manager = new CaffeineCacheManager();
                        manager.setCacheSpecification("maximumSize=500");
                        return manager;
                    }
                }
                """));
        gone(Rules.CACHE_UNBOUNDED, "no @EnableCaching", with(before, java("ParcelApp"), PARCEL_APP));
    }

    // ---- RETRY_INACTIVE (#90) --------------------------------------------------------------------------------------

    @Test
    void retryAnnotationsWithTheirEnablingAnnotation() throws IOException {
        String client = """
                package x;

                import org.springframework.resilience.annotation.ConcurrencyLimit;
                import org.springframework.resilience.annotation.Retryable;
                import org.springframework.stereotype.Component;

                @Component
                public class CarrierClient {
                    @Retryable(maxRetries = 2)
                    public String track(String parcelId) {
                        return parcelId;
                    }

                    @ConcurrencyLimit(4)
                    public String quote(String parcelId) {
                        return parcelId;
                    }
                }
                """;
        String app = """
                package x;

                import org.springframework.boot.autoconfigure.SpringBootApplication;
                ENABLE_IMPORT
                ENABLE
                @SpringBootApplication
                public class ParcelApp {
                }
                """;
        Map<String, String> before = Map.of(java("CarrierClient"), client, java("ParcelApp"), app.replace("ENABLE_IMPORT\n", "")
                .replace("ENABLE\n", ""));
        List<WorkOrder> orders = scan(before).stream().filter(o -> o.rule().equals(Rules.RETRY_INACTIVE)).toList();
        assertThat(orders).singleElement().satisfies(o -> {
            assertThat(o.reportOnly()).as("the enabling annotation goes onto the @SpringBootApplication class").isFalse();
            assertThat(o.edits()).anySatisfy(e -> assertThat(e.text()).isEqualTo("@EnableResilientMethods\n"));
        });
        gone(Rules.RETRY_INACTIVE, "@EnableResilientMethods", with(before, java("ParcelApp"), app
                .replace("ENABLE_IMPORT", "import org.springframework.resilience.annotation.EnableResilientMethods;")
                .replace("ENABLE", "@EnableResilientMethods")));
        gone(Rules.RETRY_INACTIVE, "the post-processor beans", with(before, java("ResilienceConfig"), """
                package x;

                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.resilience.annotation.ConcurrencyLimitBeanPostProcessor;
                import org.springframework.resilience.annotation.RetryAnnotationBeanPostProcessor;

                @Configuration
                public class ResilienceConfig {
                    @Bean
                    static RetryAnnotationBeanPostProcessor retryProcessor() {
                        return new RetryAnnotationBeanPostProcessor();
                    }

                    @Bean
                    static ConcurrencyLimitBeanPostProcessor limitProcessor() {
                        return new ConcurrencyLimitBeanPostProcessor();
                    }
                }
                """));
        // no @SpringBootApplication class to put it on: reported only
        assertThat(scan(Map.of(java("CarrierClient"), client))).filteredOn(o -> o.rule().equals(Rules.RETRY_INACTIVE))
                .singleElement().satisfies(o -> assertThat(o.reportOnly()).isTrue());
    }

    @Test
    void springRetryWithEnableRetry() throws IOException {
        // Spring Retry is not on the fixture's classpath: the annotation is told apart from Framework 7's by its import
        String client = """
                package x;

                import org.springframework.retry.annotation.Retryable;
                import org.springframework.stereotype.Component;

                @Component
                public class CarrierClient {
                    @Retryable
                    public String track(String parcelId) {
                        return parcelId;
                    }
                }
                """;
        String app = """
                package x;

                import org.springframework.boot.autoconfigure.SpringBootApplication;
                import org.springframework.retry.annotation.EnableRetry;

                ENABLE
                @SpringBootApplication
                public class ParcelApp {
                }
                """;
        Map<String, String> before = Map.of(java("CarrierClient"), client, java("ParcelApp"), app.replace("ENABLE\n", ""));
        // nor is AspectJ, which Spring Retry's annotations need at runtime: reported, not applied
        assertThat(scan(before)).filteredOn(o -> o.rule().equals(Rules.RETRY_INACTIVE)).singleElement()
                .satisfies(o -> assertThat(o.reportOnly()).isTrue());
        gone(Rules.RETRY_INACTIVE, "@EnableRetry", with(before, java("ParcelApp"), app.replace("ENABLE", "@EnableRetry")));
    }

    // ---- BLOCKING_WAIT_UNBOUNDED -----------------------------------------------------------------------------------

    @Test
    void requestWaitsWithABound() throws IOException {
        String controller = """
                package x;

                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.TimeUnit;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class QuoteController {
                    @GetMapping("/quote")
                    public String quote() throws Exception {
                        CompletableFuture<String> quote = CompletableFuture.supplyAsync(() -> "quote");
                        return %s;
                    }
                }
                """;
        fires(Rules.BLOCKING_WAIT_UNBOUNDED, Map.of(java("QuoteController"), controller.formatted("quote.join()")));
        gone(Rules.BLOCKING_WAIT_UNBOUNDED, "get(5, TimeUnit.SECONDS)", Map.of(java("QuoteController"),
                controller.formatted("quote.get(5, TimeUnit.SECONDS)")));
        gone(Rules.BLOCKING_WAIT_UNBOUNDED, "orTimeout(..) (#82)", Map.of(java("QuoteController"),
                controller.formatted("quote.orTimeout(5, TimeUnit.SECONDS).join()")));
        gone(Rules.BLOCKING_WAIT_UNBOUNDED, "a bounded wait on the future first (#82)", Map.of(java("QuoteController"),
                controller.replace("return %s;", "CompletableFuture.allOf(quote).get(5, TimeUnit.SECONDS);\n"
                        + "        return quote.join();")));
    }

    // ---- REMOTE_CALL_IN_TRANSACTION --------------------------------------------------------------------------------

    @Test
    void remoteCallAfterCommit() throws IOException {
        String booking = """
                package x;

                import org.springframework.context.ApplicationEventPublisher;
                import org.springframework.jdbc.core.JdbcTemplate;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;
                import org.springframework.transaction.support.TransactionSynchronization;
                import org.springframework.transaction.support.TransactionSynchronizationManager;
                import org.springframework.web.client.RestClient;

                @Service
                public class Booking {
                    private final JdbcTemplate jdbc;
                    private final RestClient carrier;
                    private final ApplicationEventPublisher events;

                    public Booking(JdbcTemplate jdbc, RestClient.Builder builder, ApplicationEventPublisher events) {
                        this.jdbc = jdbc;
                        this.carrier = builder.baseUrl("http://carrier").build();
                        this.events = events;
                    }

                    @Transactional
                    public void book(String parcelId) {
                        jdbc.update("UPDATE parcel SET booked = TRUE WHERE id = ?", parcelId);
                %s    }

                    public void notifyCarrier(String parcelId) {
                        carrier.post().uri("/bookings/{id}", parcelId).retrieve().toBodilessEntity();
                    }
                }
                """;
        fires(Rules.REMOTE_CALL_IN_TRANSACTION, Map.of(java("Booking"), booking.formatted("""
                        notifyCarrier(parcelId);
                """)));
        gone(Rules.REMOTE_CALL_IN_TRANSACTION, "@TransactionalEventListener", Map.of(java("Booking"), booking.formatted("""
                        events.publishEvent(new Booked(parcelId));
                """), java("Booked"), "package x;\n\npublic record Booked(String parcelId) {\n}\n", java("CarrierNotifier"), """
                package x;

                import org.springframework.stereotype.Component;
                import org.springframework.transaction.event.TransactionalEventListener;

                @Component
                public class CarrierNotifier {
                    private final Booking booking;

                    public CarrierNotifier(Booking booking) {
                        this.booking = booking;
                    }

                    @TransactionalEventListener
                    public void onBooked(Booked event) {
                        booking.notifyCarrier(event.parcelId());
                    }
                }
                """));
        gone(Rules.REMOTE_CALL_IN_TRANSACTION, "TransactionSynchronization.afterCommit", Map.of(java("Booking"), booking.formatted("""
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                notifyCarrier(parcelId);
                            }
                        });
                """)));
    }

    // ---- ASYNC_CONTEXT_LOSS ----------------------------------------------------------------------------------------

    @Test
    void asyncWorkOnASpringTaskExecutor() throws IOException {
        String controller = """
                package x;

                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.Executor;
                import org.springframework.core.task.TaskExecutor;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class RefreshController {
                    private final Executor pool;
                    private final TaskExecutor tasks;

                    public RefreshController(Executor pool, TaskExecutor tasks) {
                        this.pool = pool;
                        this.tasks = tasks;
                    }

                    @GetMapping("/refresh")
                    public void refresh() {
                        CompletableFuture.runAsync(() -> System.out.println("refresh")%s);
                    }
                }
                """;
        // the starter's ContextExecutors.wrap(Executor) returns a plain Executor
        String wrap = "package x;\n\nimport java.util.concurrent.Executor;\n\npublic final class ContextExecutors {\n"
                + "    public static Executor wrap(Executor delegate) {\n        return delegate;\n    }\n}\n";
        fires(Rules.ASYNC_CONTEXT_LOSS, Map.of(java("RefreshController"), controller.formatted("")));
        gone(Rules.ASYNC_CONTEXT_LOSS, "a Spring TaskExecutor bean", Map.of(java("RefreshController"), controller.formatted(", tasks")));
        gone(Rules.ASYNC_CONTEXT_LOSS, "ContextExecutors.wrap(executor)", Map.of(java("RefreshController"), controller.formatted(", ContextExecutors.wrap(pool)"), java("ContextExecutors"), wrap));
    }

    // ---- AUDIT_LOG_MIXED -------------------------------------------------------------------------------------------

    @Test
    void auditRecordsOnTheirOwnLogger() throws IOException {
        String auditLog = """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.stereotype.Component;

                @Component
                public class AuditLog {
                    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);
                %s
                    public void record(String action, String target) {
                        %s.info("Audit record written [action={}, target={}]", action, target);
                    }

                    public void checkCapacity(int limit) {
                        log.warn("Audit store is almost full [limit={}]", limit);
                    }
                }
                """;
        Map<String, String> before = Map.of(java("AuditLog"), auditLog.formatted("", "log"), java("ParcelService"), """
                package x;

                import org.springframework.stereotype.Service;

                @Service
                public class ParcelService {
                    private final AuditLog audit;

                    public ParcelService(AuditLog audit) {
                        this.audit = audit;
                    }

                    public void close(String parcelId) {
                        audit.record("parcel.close", parcelId);
                    }
                }
                """);
        fires(Rules.AUDIT_LOG_MIXED, before);
        String auditLogger = "    private static final Logger AUDIT = LoggerFactory.getLogger(\"audit\");\n";
        gone(Rules.AUDIT_LOG_MIXED, "the records on their own logger", with(before, java("AuditLog"),
                auditLog.formatted(auditLogger, "AUDIT")));
        // near miss: the diagnostics moved to the audit logger with the records, so they leave the application log
        fires(Rules.AUDIT_LOG_MIXED, with(before, java("AuditLog"),
                auditLog.formatted(auditLogger, "AUDIT").replace("log.warn", "AUDIT.warn")));
    }

    // ---- AUDIT_MISSING, AUDIT_FAILURES_UNRECORDED, AUDIT_RECORD_INCOMPLETE -----------------------------------------

    /** A DELETE endpoint (an auditable action by default) with BODY as its body and PARAMS after the path variable. */
    private static final String PARCEL_CONTROLLER = """
            package x;

            import java.security.Principal;
            import org.springframework.web.bind.annotation.DeleteMapping;
            import org.springframework.web.bind.annotation.PathVariable;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            public class ParcelController {
                private final ParcelStore store;
                private final ParcelAudit audit;

                public ParcelController(ParcelStore store, ParcelAudit audit) {
                    this.store = store;
                    this.audit = audit;
                }

                @DeleteMapping("/parcels/{parcelId}")
                public void delete(@PathVariable String parcelIdPARAMS) {
            BODY    }
            }
            """;

    private static Map<String, String> parcelController(String params, String body, String audit) {
        return Map.of(java("ParcelController"), PARCEL_CONTROLLER.replace("PARAMS", params).replace("BODY", body),
                java("ParcelStore"), """
                        package x;

                        import java.util.Map;
                        import java.util.concurrent.ConcurrentHashMap;
                        import org.springframework.stereotype.Component;

                        @Component
                        public class ParcelStore {
                            private final Map<String, String> parcels = new ConcurrentHashMap<>();

                            public void remove(String parcelId) {
                                parcels.remove(parcelId);
                            }
                        }
                        """, java("ParcelAudit"), audit);
    }

    /** An audit class (named *Audit*) whose records go to a store, not to a log. */
    private static final String PARCEL_AUDIT = """
            package x;

            import java.util.List;
            import java.util.concurrent.CopyOnWriteArrayList;
            import org.slf4j.MDC;
            import org.springframework.stereotype.Component;

            @Component
            public class ParcelAudit {
                private final List<String> entries = new CopyOnWriteArrayList<>();

                public void record(String action, String parcelId, String actor) {
                    entries.add(MDC.get("traceId") + " " + actor + " " + action + " " + parcelId);
                }

                public void record(String action, String parcelId) {
                    entries.add(MDC.get("traceId") + " " + action + " " + parcelId);
                }
            }
            """;

    @Test
    void auditableActionRecordsAnAuditEvent() throws IOException {
        fires(Rules.AUDIT_MISSING, parcelController("", "        store.remove(parcelId);\n", PARCEL_AUDIT));
        gone(Rules.AUDIT_MISSING, "an audit event with actor and the affected id", parcelController(", Principal principal", """
                        store.remove(parcelId);
                        audit.record("parcel.delete", parcelId, principal.getName());
                """, PARCEL_AUDIT));
    }

    @Test
    void auditRecordsTheFailedAttemptToo() throws IOException {
        fires(Rules.AUDIT_FAILURES_UNRECORDED, parcelController(", Principal principal", """
                        store.remove(parcelId);
                        audit.record("parcel.delete", parcelId, principal.getName());
                """, PARCEL_AUDIT));
        gone(Rules.AUDIT_FAILURES_UNRECORDED, "the audit call in a finally", parcelController(", Principal principal", """
                        try {
                            store.remove(parcelId);
                        } finally {
                            audit.record("parcel.delete", parcelId, principal.getName());
                        }
                """, PARCEL_AUDIT));
        gone(Rules.AUDIT_FAILURES_UNRECORDED, "the audit call in a catch", parcelController(", Principal principal", """
                        try {
                            store.remove(parcelId);
                        } catch (RuntimeException e) {
                            audit.record("parcel.delete.failed", parcelId, principal.getName());
                            throw e;
                        }
                        audit.record("parcel.delete", parcelId, principal.getName());
                """, PARCEL_AUDIT));
        gone(Rules.AUDIT_FAILURES_UNRECORDED, "the attempt recorded before the work", parcelController(", Principal principal", """
                        audit.record("parcel.delete", parcelId, principal.getName());
                        store.remove(parcelId);
                """, PARCEL_AUDIT));
    }

    @Test
    void auditRecordSaysWhoDidIt() throws IOException {
        Map<String, String> before = parcelController("", """
                        audit.record("parcel.delete.done", parcelId);
                        store.remove(parcelId);
                """, PARCEL_AUDIT);
        fires(Rules.AUDIT_RECORD_INCOMPLETE, before);
        gone(Rules.AUDIT_RECORD_INCOMPLETE, "the acting user from a Principal parameter", parcelController(", Principal principal", """
                        audit.record("parcel.delete.done", parcelId, principal.getName());
                        store.remove(parcelId);
                """, PARCEL_AUDIT));
        // Spring Security is not on the fixture's classpath: SecurityContextHolder stays unresolved, read by its name
        gone(Rules.AUDIT_RECORD_INCOMPLETE, "the audit method reads the user itself", with(before, java("ParcelAudit"),
                PARCEL_AUDIT.replace("""
                                public void record(String action, String parcelId) {
                                    entries.add(MDC.get("traceId") + " " + action + " " + parcelId);
                                }
                            """, """
                                public void record(String action, String parcelId) {
                                    String actor = org.springframework.security.core.context.SecurityContextHolder.getContext()
                                            .getAuthentication().getName();
                                    entries.add(MDC.get("traceId") + " " + actor + " " + action + " " + parcelId);
                                }
                            """)));
    }

    @Test
    void auditRecordStatesOutcomeAndRequest() throws IOException {
        String audit = """
                package x;

                import java.util.List;
                import java.util.concurrent.CopyOnWriteArrayList;
                import org.slf4j.MDC;
                import org.springframework.stereotype.Component;

                @Component
                public class ParcelAudit {
                    private final List<String> entries = new CopyOnWriteArrayList<>();

                    public void record(String action, String parcelId, String actorOUTCOME) {
                        entries.add(TRACEactor + " " + action + " " + parcelId);
                    }
                }
                """;
        String call = """
                        audit.record("parcel.delete", parcelId, principal.getName()OUTCOME);
                        store.remove(parcelId);
                """;
        fires(Rules.AUDIT_RECORD_INCOMPLETE, parcelController(", Principal principal", call.replace("OUTCOME", ""),
                audit.replace("OUTCOME", "").replace("TRACE", "")));
        // near miss: the trace id read, but no outcome
        fires(Rules.AUDIT_RECORD_INCOMPLETE, parcelController(", Principal principal", call.replace("OUTCOME", ""),
                audit.replace("OUTCOME", "").replace("TRACE", "MDC.get(\"traceId\") + \" \" + ")));
        gone(Rules.AUDIT_RECORD_INCOMPLETE, "an outcome parameter, the trace id read from MDC", parcelController(", Principal principal",
                call.replace("OUTCOME", ", \"succeeded\""), audit.replace("OUTCOME", ", String outcome")
                        .replace("TRACE", "MDC.get(\"traceId\") + \" \" + outcome + \" \" + ")));
        gone(Rules.AUDIT_RECORD_INCOMPLETE, "the outcome and the trace id passed in", parcelController(", Principal principal",
                call.replace("OUTCOME", ", \"failed\", org.slf4j.MDC.get(\"traceId\")"),
                audit.replace("OUTCOME", ", String outcome, String traceId").replace("TRACE", "traceId + \" \" + outcome + \" \" + ")));
    }

    // ---- AUDIT_LOGGER_UNROUTED -------------------------------------------------------------------------------------

    @Test
    void auditLoggerRoutedApart() throws IOException {
        String logback = """
                <configuration>
                    <appender name="FILE" class="ch.qos.logback.core.FileAppender">
                        <file>service.log</file>
                        <encoder><pattern>%msg%n</pattern></encoder>
                    </appender>
                    <appender name="AUDIT" class="ch.qos.logback.core.FileAppender">
                        <file>audit.log</file>
                        <encoder><pattern>%msg%n</pattern></encoder>
                    </appender>
                LOGGER    <root level="INFO">
                        <appender-ref ref="FILE"/>
                    </root>
                </configuration>
                """;
        Map<String, String> before = Map.of(java("ParcelAudit"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.stereotype.Component;

                @Component
                public class ParcelAudit {
                    private static final Logger AUDIT = LoggerFactory.getLogger("audit");

                    public void record(String action, String parcelId) {
                        AUDIT.info("Parcel audit record [action={}, parcelId={}]", action, parcelId);
                    }
                }
                """, java("ParcelService"), """
                package x;

                import org.springframework.stereotype.Service;

                @Service
                public class ParcelService {
                    private final ParcelAudit audit;

                    public ParcelService(ParcelAudit audit) {
                        this.audit = audit;
                    }

                    public void close(String parcelId) {
                        audit.record("parcel.close", parcelId);
                    }
                }
                """, "src/main/resources/logback-spring.xml", logback.replace("LOGGER", ""));
        fires(Rules.AUDIT_LOGGER_UNROUTED, before);
        // near miss: the logger declared, but additivity off and no appender: the records are dropped
        fires(Rules.AUDIT_LOGGER_UNROUTED, with(before, "src/main/resources/logback-spring.xml",
                logback.replace("LOGGER", "    <logger name=\"audit\" additivity=\"false\"/>\n")));
        gone(Rules.AUDIT_LOGGER_UNROUTED, "a logger for audit with its own appender", with(before, "src/main/resources/logback-spring.xml",
                logback.replace("LOGGER", "    <logger name=\"audit\" level=\"INFO\" additivity=\"false\">\n"
                        + "        <appender-ref ref=\"AUDIT\"/>\n    </logger>\n")));
        gone(Rules.AUDIT_LOGGER_UNROUTED, "no logback file: Boot's structured logging carries the logger name",
                with(before, "src/main/resources/logback-spring.xml", null));
        // near miss: logging.level.audit above the records' level drops them
        fires(Rules.AUDIT_LOGGER_UNROUTED, with(before, "src/main/resources/logback-spring.xml", null,
                "src/main/resources/application.yml", "spring:\n  application:\n    name: remedy\nlogging:\n  level:\n    audit: ERROR\n"));
    }

    // ---- AUDIT_BEFORE_COMMIT ---------------------------------------------------------------------------------------

    @Test
    void auditRecordWrittenWhenTheOutcomeIsKnown() throws IOException {
        String auditLog = """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.stereotype.Component;

                @Component
                public class ParcelAudit {
                    private static final Logger AUDIT = LoggerFactory.getLogger("audit");

                    public void record(String action, String parcelId, String outcome) {
                        AUDIT.info("Parcel audit record [action={}, parcelId={}, outcome={}]", action, parcelId, outcome);
                    }
                }
                """;
        String service = """
                package x;

                import org.springframework.context.ApplicationEventPublisher;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;
                import org.springframework.transaction.event.TransactionalEventListener;

                @Service
                public class ParcelService {
                    private final ParcelAudit audit;
                    private final ApplicationEventPublisher events;

                    public ParcelService(ParcelAudit audit, ApplicationEventPublisher events) {
                        this.audit = audit;
                        this.events = events;
                    }

                    public record ParcelClosed(String parcelId) {
                    }

                    @Transactional
                    public void close(String parcelId) {
                BODY    }

                    @TransactionalEventListener
                    public void onClosed(ParcelClosed event) {
                        audit.record("parcel.close", event.parcelId(), "done");
                    }
                }
                """;
        Map<String, String> before = Map.of(java("ParcelAudit"), auditLog, java("ParcelService"),
                service.replace("BODY", "        audit.record(\"parcel.close\", parcelId, \"done\");\n"));
        fires(Rules.AUDIT_BEFORE_COMMIT, before);
        gone(Rules.AUDIT_BEFORE_COMMIT, "an event recorded by a @TransactionalEventListener after commit", with(before,
                java("ParcelService"), service.replace("BODY", "        events.publishEvent(new ParcelClosed(parcelId));\n")));
        gone(Rules.AUDIT_BEFORE_COMMIT, "only the failure recorded in the transaction, from a catch", with(before,
                java("ParcelService"), service.replace("BODY", """
                                try {
                                    events.publishEvent(new ParcelClosed(parcelId));
                                } catch (RuntimeException e) {
                                    audit.record("parcel.close", parcelId, "failed");
                                    throw e;
                                }
                        """)));
    }

    // ---- AUDIT_RECORD_SECRET ---------------------------------------------------------------------------------------

    @Test
    void auditRecordWithoutSecrets() throws IOException {
        String audit = """
                package x;

                import java.util.List;
                import java.util.concurrent.CopyOnWriteArrayList;
                import org.springframework.stereotype.Component;

                @Component
                public class AccountAudit {
                    private final List<String> entries = new CopyOnWriteArrayList<>();

                    public void record(String action, Object details, String outcome) {
                        entries.add(action + " " + details + " " + outcome);
                    }
                }
                """;
        String service = """
                package x;

                import org.springframework.stereotype.Service;

                @Service
                public class AccountService {
                    private final AccountAudit audit;

                    public AccountService(AccountAudit audit) {
                        this.audit = audit;
                    }

                    public void changePassword(String accountId, String newPassword) {
                        String secret = newPassword;
                        audit.record("account.password", DETAILS, "done");
                    }
                }
                """;
        fires(Rules.AUDIT_RECORD_SECRET, Map.of(java("AccountAudit"), audit, java("AccountService"), service.replace("DETAILS", "secret")));
        gone(Rules.AUDIT_RECORD_SECRET, "record that it changed, not the value", Map.of(java("AccountAudit"), audit,
                java("AccountService"), service.replace("DETAILS", "accountId")));
        gone(Rules.AUDIT_RECORD_SECRET, "a derived value: its length", Map.of(java("AccountAudit"), audit,
                java("AccountService"), service.replace("DETAILS", "newPasswordLength(newPassword)")
                        .replace("    }\n}", "    }\n\n    private static int newPasswordLength(String p) {\n        return p.length();\n    }\n}")));
    }

    // ---- AUDIT_ACTOR_LOST_ASYNC ------------------------------------------------------------------------------------

    @Test
    void auditActorReadBeforeTheHandOver() throws IOException {
        String audit = """
                package x;

                import java.util.List;
                import java.util.concurrent.CopyOnWriteArrayList;
                import org.springframework.stereotype.Component;

                @Component
                public class ParcelAudit {
                    private final List<String> entries = new CopyOnWriteArrayList<>();

                    public void recordCurrent(String action, String parcelId) {
                        String actor = org.springframework.security.core.context.SecurityContextHolder.getContext()
                                .getAuthentication().getName();
                        entries.add(actor + " " + action + " " + parcelId);
                    }

                    public void record(String action, String parcelId, String actor) {
                        entries.add(actor + " " + action + " " + parcelId);
                    }
                }
                """;
        String service = """
                package x;

                import java.security.Principal;
                import org.springframework.scheduling.annotation.Async;
                import org.springframework.stereotype.Service;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class ParcelController {
                    private final ParcelArchive archive;

                    public ParcelController(ParcelArchive archive) {
                        this.archive = archive;
                    }

                    @PostMapping("/parcels/{parcelId}/archive")
                    public void archive(@PathVariable String parcelId, Principal principal) {
                        archive.archive(parcelId, principal.getName());
                    }
                }

                @Service
                class ParcelArchive {
                    private final ParcelAudit audit;

                    ParcelArchive(ParcelAudit audit) {
                        this.audit = audit;
                    }

                    @Async
                    public void archive(String parcelId, String actor) {
                        RECORD
                    }
                }
                """;
        Map<String, String> before = Map.of(java("ParcelAudit"), audit, java("ParcelController"),
                service.replace("RECORD", "audit.recordCurrent(\"parcel.archive\", parcelId);"));
        fires(Rules.AUDIT_ACTOR_LOST_ASYNC, before);
        gone(Rules.AUDIT_ACTOR_LOST_ASYNC, "the actor read on the request thread and passed in", with(before, java("ParcelController"),
                service.replace("RECORD", "audit.record(\"parcel.archive\", parcelId, actor);")));
        gone(Rules.AUDIT_ACTOR_LOST_ASYNC, "a DelegatingSecurityContextAsyncTaskExecutor carries the context", with(before,
                java("AsyncConfig"), """
                        package x;

                        import org.springframework.context.annotation.Bean;
                        import org.springframework.context.annotation.Configuration;
                        import org.springframework.core.task.AsyncTaskExecutor;
                        import org.springframework.core.task.SimpleAsyncTaskExecutor;

                        @Configuration
                        public class AsyncConfig {
                            @Bean
                            AsyncTaskExecutor taskExecutor() {
                                return new org.springframework.security.task.DelegatingSecurityContextAsyncTaskExecutor(
                                        new SimpleAsyncTaskExecutor());
                            }
                        }
                        """));
    }

    // ---- CATCH_ALL_DEFAULT -----------------------------------------------------------------------------------------

    @Test
    void catchAllNoLongerReturnsADefault() throws IOException {
        String client = """
                package x;

                import org.springframework.stereotype.Component;

                @Component
                public class PointsClient {
                    public long balance(String raw) {
                        try {
                            return Long.parseLong(raw);
                        } catch (CAUGHT e) {
                            HANDLED
                        }
                    }
                }
                """;
        fires(Rules.CATCH_ALL_DEFAULT, Map.of(java("PointsClient"), client.replace("CAUGHT", "Exception").replace("HANDLED", "return 0L;")));
        gone(Rules.CATCH_ALL_DEFAULT, "catch only what means \"nothing there\"", Map.of(java("PointsClient"),
                client.replace("CAUGHT", "NumberFormatException").replace("HANDLED", "return 0L;")));
        gone(Rules.CATCH_ALL_DEFAULT, "wrap it", Map.of(java("PointsClient"), client.replace("CAUGHT", "Exception")
                .replace("HANDLED", "throw new IllegalStateException(\"Balance unreadable\", e);")));
    }

    // ---- CAUSE_DROPPED ---------------------------------------------------------------------------------------------

    @Test
    void newExceptionCarriesTheCause() throws IOException {
        String verifier = """
                package x;

                import org.springframework.stereotype.Component;

                @Component
                public class ParcelVerifier {
                    public void verify(String parcelId) {
                        try {
                            check(parcelId);
                        } catch (IllegalStateException e) {
                            THROW
                        }
                    }

                    private void check(String parcelId) {
                        if (parcelId.isBlank()) {
                            throw new IllegalStateException("blank parcel id");
                        }
                    }
                }
                """;
        String rejected = """
                package x;

                public class ParcelRejected extends RuntimeException {
                    public ParcelRejected(String message) {
                        super(message);
                    }
                CAUSE}
                """;
        fires(Rules.CAUSE_DROPPED, Map.of(java("ParcelVerifier"), verifier.replace("THROW", "throw new ParcelRejected(e.getMessage());"),
                java("ParcelRejected"), rejected.replace("CAUSE", "")));
        gone(Rules.CAUSE_DROPPED, "a constructor (…, Throwable cause), e passed to it", Map.of(java("ParcelVerifier"),
                verifier.replace("THROW", "throw new ParcelRejected(e.getMessage(), e);"), java("ParcelRejected"), rejected.replace("CAUSE", """

                            public ParcelRejected(String message, Throwable cause) {
                                super(message, cause);
                            }
                        """)));
        gone(Rules.CAUSE_DROPPED, "initCause(e) before the throw", Map.of(java("ParcelVerifier"), verifier.replace("THROW", """
                ParcelRejected rejected = new ParcelRejected(e.getMessage());
                            rejected.initCause(e);
                            throw rejected;"""), java("ParcelRejected"), rejected.replace("CAUSE", "")));
    }

    // ---- CLIENT_ERROR_AS_SERVER_ERROR, ERROR_DETAILS_EXPOSED -------------------------------------------------------

    private static final String API_ERRORS = """
            package x;

            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            import org.springframework.http.HttpStatus;
            import org.springframework.http.ProblemDetail;
            import org.springframework.http.ResponseEntity;
            import org.springframework.web.bind.annotation.ExceptionHandler;
            import org.springframework.web.bind.annotation.RestControllerAdvice;
            import org.springframework.web.servlet.resource.NoResourceFoundException;

            @RestControllerAdvice
            public class ApiErrors {
                private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

                @ExceptionHandler(Exception.class)
                public ProblemDetail unexpected(Exception e) {
            UNEXPECTED    }

                @ExceptionHandler(NoResourceFoundException.class)
                public ResponseEntity<String> missing(NoResourceFoundException e) {
                    return ResponseEntity.status(HttpStatus.MISSING).body("not found");
                }
            }
            """;
    private static final String LEAKS = "        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());\n";
    private static final String FIXED_TEXT = """
                    log.error("Request failed", e);
                    return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
            """;

    /** The advice with UNEXPECTED as the catch-all's body and MISSING as the not-found status, and an HTTP endpoint. */
    private static Map<String, String> apiErrors(String unexpected, String missing) {
        return Map.of(java("ApiErrors"), API_ERRORS.replace("UNEXPECTED", unexpected).replace("MISSING", missing),
                java("ParcelController"), """
                        package x;

                        import org.springframework.web.bind.annotation.GetMapping;
                        import org.springframework.web.bind.annotation.PathVariable;
                        import org.springframework.web.bind.annotation.RestController;

                        @RestController
                        public class ParcelController {
                            @GetMapping("/parcels/{parcelId}")
                            public String parcel(@PathVariable String parcelId) {
                                return parcelId;
                            }
                        }
                        """);
    }

    @Test
    void clientErrorAnswers4xx() throws IOException {
        fires(Rules.CLIENT_ERROR_AS_SERVER_ERROR, apiErrors(FIXED_TEXT, "INTERNAL_SERVER_ERROR"));
        gone(Rules.CLIENT_ERROR_AS_SERVER_ERROR, "answer 404", apiErrors(FIXED_TEXT, "NOT_FOUND"));
    }

    @Test
    void unexpectedErrorAnswersAFixedText() throws IOException {
        fires(Rules.ERROR_DETAILS_EXPOSED, apiErrors(LEAKS, "NOT_FOUND"));
        gone(Rules.ERROR_DETAILS_EXPOSED, "a fixed text, the exception logged", apiErrors(FIXED_TEXT, "NOT_FOUND"));
    }

    // ---- UNMAPPED_EXCEPTION ----------------------------------------------------------------------------------------

    @Test
    void exceptionOnAnHttpPathGetsAStatus() throws IOException {
        String locked = """
                package x;

                IMPORTS
                ANNOTATIONpublic class ParcelLocked extends SUPER {
                    public ParcelLocked(String parcelId) {
                        super(ARGS"parcel locked: " + parcelId);
                    }
                }
                """;
        Map<String, String> before = Map.of(java("ParcelController"), """
                package x;

                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class ParcelController {
                    @PostMapping("/parcels/{parcelId}/close")
                    public void close(@PathVariable String parcelId) {
                        if (parcelId.startsWith("L")) {
                            throw new ParcelLocked(parcelId);
                        }
                    }
                }
                """, java("ParcelLocked"), locked.replace("IMPORTS", "").replace("ANNOTATION", "").replace("SUPER", "RuntimeException")
                .replace("ARGS", ""));
        fires(Rules.UNMAPPED_EXCEPTION, before);
        gone(Rules.UNMAPPED_EXCEPTION, "@ResponseStatus(HttpStatus.CONFLICT) on the class", with(before, java("ParcelLocked"), locked
                .replace("IMPORTS", "import org.springframework.http.HttpStatus;\nimport org.springframework.web.bind.annotation.ResponseStatus;\n")
                .replace("ANNOTATION", "@ResponseStatus(HttpStatus.CONFLICT)\n").replace("SUPER", "RuntimeException").replace("ARGS", "")));
        gone(Rules.UNMAPPED_EXCEPTION, "an @ExceptionHandler returning a ProblemDetail", with(before, java("ApiErrors"), """
                package x;

                import org.springframework.http.HttpStatus;
                import org.springframework.http.ProblemDetail;
                import org.springframework.web.bind.annotation.ExceptionHandler;
                import org.springframework.web.bind.annotation.RestControllerAdvice;

                @RestControllerAdvice
                public class ApiErrors {
                    @ExceptionHandler(ParcelLocked.class)
                    public ProblemDetail locked(ParcelLocked e) {
                        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "parcel-locked");
                    }
                }
                """));
        gone(Rules.UNMAPPED_EXCEPTION, "it extends ResponseStatusException", with(before, java("ParcelLocked"), locked
                .replace("IMPORTS", "import org.springframework.http.HttpStatus;\nimport org.springframework.web.server.ResponseStatusException;\n")
                .replace("ANNOTATION", "").replace("SUPER", "ResponseStatusException").replace("ARGS", "HttpStatus.CONFLICT, ")));
    }

    // ---- DB_NO_TIMEOUT ---------------------------------------------------------------------------------------------

    @Test
    void transactionsWithATimeout() throws IOException {
        String service = """
                package x;

                import org.springframework.jdbc.core.JdbcTemplate;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;

                @Service
                public class ParcelService {
                    private final JdbcTemplate jdbc;

                    public ParcelService(JdbcTemplate jdbc) {
                        this.jdbc = jdbc;
                    }

                    @TRANSACTIONAL
                    public void close(String parcelId) {
                        jdbc.update("UPDATE parcel SET closed = TRUE WHERE id = ?", parcelId);
                    }
                }
                """;
        // the JdbcTemplate's query timeout (an AUTO order of the same rule) is set in all of them, so only the
        // transaction finding differs
        String config = """
                spring:
                  application:
                    name: remedy
                  jdbc:
                    template:
                      query-timeout: 30s
                """;
        Map<String, String> before = Map.of(java("ParcelService"), service.replace("TRANSACTIONAL", "Transactional"),
                "src/main/resources/application.yml", config);
        fires(Rules.DB_NO_TIMEOUT, before);
        gone(Rules.DB_NO_TIMEOUT, "spring.transaction.default-timeout", with(before, "src/main/resources/application.yml",
                config + "  transaction:\n    default-timeout: 30s\n"));
        gone(Rules.DB_NO_TIMEOUT, "timeout = … on the @Transactional methods",
                with(before, java("ParcelService"), service.replace("TRANSACTIONAL", "Transactional(timeout = 30)")));
        String classLevel = service.replace("@Service\n", "@Service\n@Transactional(timeout = 30)\n");
        gone(Rules.DB_NO_TIMEOUT, "timeout = … on the class's @Transactional", with(before, java("ParcelService"),
                classLevel.replace("    @TRANSACTIONAL\n", "")));
        // a method's own @Transactional replaces the class's, timeout included
        fires(Rules.DB_NO_TIMEOUT, with(before, java("ParcelService"), classLevel.replace("TRANSACTIONAL", "Transactional(readOnly = true)")));
        // #85: not only next to a JdbcTemplate; a JPA-only service's transactions have no timeout either
        fires(Rules.DB_NO_TIMEOUT, with(before, java("ParcelService"), """
                package x;

                import jakarta.persistence.EntityManager;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;

                @Service
                public class ParcelService {
                    private final EntityManager em;

                    public ParcelService(EntityManager em) {
                        this.em = em;
                    }

                    @Transactional
                    public void close(String parcelId) {
                        em.createQuery("UPDATE Parcel p SET p.closed = TRUE WHERE p.id = :id").setParameter("id", parcelId).executeUpdate();
                    }
                }
                """));
    }

    /** #41: a JdbcTemplate made with new is out of spring.jdbc.template.*'s reach; JPA gets its own property. */
    @Test
    void handMadeJdbcTemplateWithAQueryTimeoutAndJpaWithItsProperty() throws IOException {
        String rates = """
                package x;

                import javax.sql.DataSource;
                import org.springframework.jdbc.core.JdbcTemplate;
                import org.springframework.stereotype.Repository;

                @Repository
                public class RateRepository {
                    private final JdbcTemplate jdbc;

                    public RateRepository(DataSource dataSource) {
                        this.jdbc = new JdbcTemplate(dataSource);
                %s    }

                    public Integer rate(String zone) {
                        return jdbc.queryForObject("SELECT rate FROM rate WHERE zone = ?", Integer.class, zone);
                    }
                }
                """;
        String config = CONFIG + "  jdbc:\n    template:\n      query-timeout: 30s\n";
        Map<String, String> before = Map.of(java("RateRepository"), rates.formatted(""), "src/main/resources/application.yml", config);
        assertThat(scan(before)).filteredOn(o -> o.rule().equals(Rules.DB_NO_TIMEOUT)).as("the property is set and still")
                .singleElement().satisfies(o -> assertThat(o.title()).startsWith("new JdbcTemplate(..)"));
        gone(Rules.DB_NO_TIMEOUT, "setQueryTimeout(..) on it", with(before, java("RateRepository"),
                rates.formatted("        this.jdbc.setQueryTimeout(30);\n")));

        // JPA (not on the fixture's classpath: @Entity is known by its import)
        Map<String, String> jpa = Map.of(java("Parcel"), """
                package x;

                import jakarta.persistence.Entity;

                @Entity
                public class Parcel {
                    private String parcelId;
                }
                """);
        assertThat(scan(jpa)).filteredOn(o -> o.rule().equals(Rules.DB_NO_TIMEOUT)).singleElement()
                .satisfies(o -> assertThat(o.edits()).singleElement().satisfies(e ->
                        assertThat(e.text()).contains("jakarta.persistence.query.timeout: 300000")));
        gone(Rules.DB_NO_TIMEOUT, "spring.jpa.properties.jakarta.persistence.query.timeout", with(jpa, "src/main/resources/application.yml",
                CONFIG + "  jpa:\n    properties:\n      \"[jakarta.persistence.query.timeout]\": 30000\n"));
        assertThat(ResilienceRules.millis("5m")).isEqualTo(300_000L);
        assertThat(ResilienceRules.millis("PT30S")).isEqualTo(30_000L);
        assertThat(ResilienceRules.millis("45")).isEqualTo(45_000L);
        assertThat(ResilienceRules.millis("500ms")).isEqualTo(500L);
    }

    // ---- HTTP_CLIENT_NO_TIMEOUT, HTTP_CLIENT_UNTRACED --------------------------------------------------------------

    private static final String STOCK_CLIENT = """
            package x;

            import org.springframework.stereotype.Component;
            import org.springframework.web.client.RestTemplate;
            IMPORTS
            @Component
            public class StockClient {
            MEMBERS
                public String stock(String sku) {
                    return rest.getForObject("http://stock-service/items/{sku}", String.class, sku);
                }
            }
            """;

    private static Map<String, String> stockClient(String imports, String members) {
        return Map.of(java("StockClient"), STOCK_CLIENT.replace("IMPORTS", imports).replace("MEMBERS", members));
    }

    private static final Map<String, String> PLAIN_REST_TEMPLATE = stockClient("",
            "    private final RestTemplate rest = new RestTemplate();\n");
    private static final Map<String, String> BUILT_REST_TEMPLATE = stockClient("import org.springframework.boot.web.client.RestTemplateBuilder;\n", """
                private final RestTemplate rest;

                public StockClient(RestTemplateBuilder builder) {
                    this.rest = builder.build();
                }
            """);

    @Test
    void httpClientFromTheInjectedBuilderOrWithTimeouts() throws IOException {
        fires(Rules.HTTP_CLIENT_NO_TIMEOUT, PLAIN_REST_TEMPLATE);
        gone(Rules.HTTP_CLIENT_NO_TIMEOUT, "built from the injected RestTemplateBuilder", BUILT_REST_TEMPLATE);
        gone(Rules.HTTP_CLIENT_NO_TIMEOUT, "a requestFactory with connect and read timeouts", stockClient(
                "import org.springframework.http.client.SimpleClientHttpRequestFactory;\n", """
                            private final RestTemplate rest;

                            public StockClient() {
                                SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                                factory.setConnectTimeout(2000);
                                factory.setReadTimeout(5000);
                                this.rest = new RestTemplate(factory);
                            }
                        """));
    }

    @Test
    void httpClientFromTheInstrumentedBuilder() throws IOException {
        fires(Rules.HTTP_CLIENT_UNTRACED, PLAIN_REST_TEMPLATE);
        gone(Rules.HTTP_CLIENT_UNTRACED, "built in the constructor from the injected RestTemplateBuilder", BUILT_REST_TEMPLATE);
    }

    // ---- LOG_AND_RETHROW -------------------------------------------------------------------------------------------

    @Test
    void logAndRethrowOnceTheContextIsInMdc() throws IOException {
        String dispatcher = """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.slf4j.MDC;
                import org.springframework.stereotype.Component;

                @Component
                public class ParcelDispatcher {
                    private static final Logger log = LoggerFactory.getLogger(ParcelDispatcher.class);

                    public void dispatch(String parcelId) {
                BODY    }

                    private void send(String parcelId) {
                        if (parcelId.isBlank()) {
                            throw new IllegalStateException("blank parcel id");
                        }
                    }
                }
                """;
        String logged = """
                        try {
                            send(parcelId);
                        } catch (IllegalStateException e) {
                            log.error("Parcel dispatch failed [parcelId={}]", parcelId, e);
                            throw e;
                        }
                """;
        String policy = PACKS + "mdc:\n  keys:\n    parcelId:\n      params: [parcelId]\n";
        Map<String, String> before = Map.of("logast.yaml", policy, java("ParcelDispatcher"), dispatcher.replace("BODY", logged));
        fires(Rules.LOG_AND_RETHROW, before);
        // #85: the done-when line names both conditions (context in MDC, and a log on every caller path), not only the first
        assertThat(scan(before)).as("the parcelId it carries is not in MDC, and nothing calls dispatch: report-only")
                .anyMatch(o -> o.rule().equals(Rules.LOG_AND_RETHROW) && o.reportOnly() && o.doneWhen().startsWith(
                        "parcelId is in MDC here and a catch or @ExceptionHandler logs it on the path through ParcelDispatcher.dispatch"));
        gone(Rules.LOG_AND_RETHROW, "the line deleted", with(before, java("ParcelDispatcher"), dispatcher.replace("BODY", """
                        send(parcelId);
                """)));
        Map<String, String> inMdc = with(before, java("ParcelDispatcher"), dispatcher.replace("BODY",
                "        try (MDC.MDCCloseable scope = MDC.putCloseable(\"parcelId\", parcelId)) {\n" + logged.indent(4) + "        }\n"));
        assertThat(scan(inMdc)).as("with parcelId in MDC, what is left is the second condition the first line named")
                .anyMatch(o -> o.rule().equals(Rules.LOG_AND_RETHROW) && o.reportOnly()
                        && o.doneWhen().startsWith("a catch or @ExceptionHandler logs it on the path through ParcelDispatcher.dispatch"));
        // with that condition met too, the finding is the caller's log line logging it again, which logast deletes itself
        Map<String, String> loggedByCaller = with(inMdc, java("DispatchJob"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.stereotype.Component;

                @Component
                public class DispatchJob {
                    private static final Logger log = LoggerFactory.getLogger(DispatchJob.class);
                    private final ParcelDispatcher dispatcher;

                    public DispatchJob(ParcelDispatcher dispatcher) {
                        this.dispatcher = dispatcher;
                    }

                    public void run(String parcelId) {
                        try {
                            dispatcher.dispatch(parcelId);
                        } catch (IllegalStateException e) {
                            log.warn("Parcel dispatch skipped [parcelId={}]", parcelId, e);
                        }
                    }
                }
                """);
        gone(Rules.LOG_AND_RETHROW, "parcelId in MDC and a caller that logs the failure", loggedByCaller);
        becomesAuto(Rules.EXCEPTION_LOGGED_TWICE, "parcelId in MDC and a caller that logs the failure", loggedByCaller);
    }

    // ---- LOG_HELPER ------------------------------------------------------------------------------------------------

    @Test
    void logHelperInlinedOrMasking() throws IOException {
        String helper = """
                package x;

                import java.util.Collection;
                import org.slf4j.Logger;

                public final class LogSupport {
                    private LogSupport() {
                    }

                    public static void logCollection(Logger log, String label, Collection<?> items) {
                        log.debug("{} [count={}]", label, items.size());
                    }
                }
                """;
        String publisher = """
                package x;

                import java.util.List;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.stereotype.Service;

                @Service
                public class ParcelPublisher {
                    private static final Logger log = LoggerFactory.getLogger(ParcelPublisher.class);

                    public void publish(List<String> parcelIds) {
                        CALL
                    }
                }
                """;
        Map<String, String> before = Map.of(java("LogSupport"), helper,
                java("ParcelPublisher"), publisher.replace("CALL", "LogSupport.logCollection(log, \"Parcels prepared\", parcelIds);"));
        fires(Rules.LOG_HELPER, before);
        gone(Rules.LOG_HELPER, "an explicit log statement at the call site", with(before, java("ParcelPublisher"),
                publisher.replace("CALL", "log.debug(\"Parcels prepared [count={}]\", parcelIds.size());")));
        gone(Rules.LOG_HELPER, "a helper that masks", with(before, java("LogSupport"), helper.replace("logCollection", "logMasked")
                .replace("label, items.size()", "label, items.stream().map(i -> \"***\").toList()"), java("ParcelPublisher"),
                publisher.replace("CALL", "LogSupport.logMasked(log, \"Parcels prepared\", parcelIds);")));
    }

    // ---- LOG_IN_LOOP, LOG_VOLUME -----------------------------------------------------------------------------------

    @Test
    void infoPerItemOfASmallLoop() throws IOException {
        String exporter = """
                package x;

                import java.util.List;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.stereotype.Component;

                @Component
                public class ManifestExporter {
                    private static final Logger log = LoggerFactory.getLogger(ManifestExporter.class);

                    public void export(List<String> manifestIds) {
                MARKER        for (String manifestId : manifestIds) {
                            log.info("Manifest exported [manifestId={}]", manifestId);
                        }
                    }
                }
                """;
        fires(Rules.LOG_IN_LOOP, Map.of(java("ManifestExporter"), exporter.replace("MARKER", "")));
        gone(Rules.LOG_IN_LOOP, "// logast:loop-size 10", Map.of(java("ManifestExporter"),
                exporter.replace("MARKER", "        // logast:loop-size 10\n")));
    }

    @Test
    void highVolumeLineAtDebug() throws IOException {
        String controller = """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class TrackingController {
                    private static final Logger log = LoggerFactory.getLogger(TrackingController.class);

                    @PostMapping("/parcels/{parcelId}/ping")
                    public void ping(@PathVariable String parcelId) {
                        log.LEVEL("Tracking ping received [parcelId={}]", parcelId);
                    }
                }
                """;
        String traffic = "entrypoints:\n  \"POST /parcels/{parcelId}/ping\": 500000\n";
        fires(Rules.LOG_VOLUME, Map.of(java("TrackingController"), controller.replace("LEVEL", "info"), ".logast/traffic.yaml", traffic));
        gone(Rules.LOG_VOLUME, "moved to DEBUG", Map.of(java("TrackingController"), controller.replace("LEVEL", "debug"),
                ".logast/traffic.yaml", traffic));
    }

    // ---- MDC_CLEAR -------------------------------------------------------------------------------------------------

    @Test
    void mdcScopedInsteadOfCleared() throws IOException {
        String job = """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.slf4j.MDC;
                import org.springframework.stereotype.Component;

                @Component
                public class ParcelSweeper {
                    private static final Logger log = LoggerFactory.getLogger(ParcelSweeper.class);

                    public void sweep(String parcelId) {
                BODY    }
                }
                """;
        fires(Rules.MDC_CLEAR, Map.of(java("ParcelSweeper"), job.replace("BODY", """
                        MDC.put("parcelId", parcelId);
                        log.info("Parcel swept");
                        MDC.clear();
                """)));
        gone(Rules.MDC_CLEAR, "a scope, no clear()", Map.of(java("ParcelSweeper"), job.replace("BODY", """
                        try (MDC.MDCCloseable scope = MDC.putCloseable("parcelId", parcelId)) {
                            log.info("Parcel swept");
                        }
                """)));
    }

    // ---- MESSAGE_VAGUE ---------------------------------------------------------------------------------------------

    @Test
    void messageNamesTheOperationAndItsOutcome() throws IOException {
        String publisher = """
                package x;

                import java.util.List;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.stereotype.Service;

                @Service
                public class ParcelPublisher {
                    private static final Logger log = LoggerFactory.getLogger(ParcelPublisher.class);

                    public void publish(List<String> parcelIds) {
                        LINE
                    }
                }
                """;
        fires(Rules.MESSAGE_VAGUE, Map.of(java("ParcelPublisher"), publisher.replace("LINE", "log.debug(\"Publishing parcels\");")));
        gone(Rules.MESSAGE_VAGUE, "\"<Subject> completed\"", Map.of(java("ParcelPublisher"), publisher.replace("LINE",
                "log.debug(\"Parcel publication completed [count={}]\", parcelIds.size());")));
    }

    // ---- METRIC_* --------------------------------------------------------------------------------------------------

    private static final String METRICS_CLASS = """
            package x;

            import io.micrometer.core.instrument.Counter;
            import io.micrometer.core.instrument.Gauge;
            import io.micrometer.core.instrument.MeterRegistry;
            import java.util.List;
            import java.util.concurrent.atomic.AtomicInteger;
            import org.springframework.stereotype.Component;

            @Component
            public class ParcelMetrics {
                private final MeterRegistry registry;
            FIELDS
                public ParcelMetrics(MeterRegistry registry) {
                    this.registry = registry;
            INIT    }

            METHODS}
            """;

    private static Map<String, String> metrics(String fields, String init, String methods) {
        return Map.of(java("ParcelMetrics"), METRICS_CLASS.replace("FIELDS", fields).replace("INIT", init).replace("METHODS", methods));
    }

    @Test
    void gaugeOnAStronglyHeldObject() throws IOException {
        fires(Rules.METRIC_GAUGE_WEAK_REF, metrics("", "", """
                    public void trackQueue() {
                        AtomicInteger queue = new AtomicInteger();
                        registry.gauge("parcel.queue", queue);
                    }
                """));
        gone(Rules.METRIC_GAUGE_WEAK_REF, "the measured object kept in a field", metrics("    private final AtomicInteger queue;\n",
                "        this.queue = registry.gauge(\"parcel.queue\", new AtomicInteger());\n", ""));
        gone(Rules.METRIC_GAUGE_WEAK_REF, "Gauge.builder(..).strongReference(true)", metrics("", "", """
                    public void trackQueue() {
                        AtomicInteger queue = new AtomicInteger();
                        Gauge.builder("parcel.queue", queue, AtomicInteger::get).strongReference(true).register(registry);
                    }
                """));
    }

    @Test
    void metricTagWithoutAnId() throws IOException {
        String policy = PACKS + "mdc:\n  keys:\n    parcelId:\n      params: [parcelId]\n";
        fires(Rules.METRIC_HIGH_CARDINALITY_TAG, with(metrics("", "", """
                    public void delivered(String parcelId, String channel) {
                        registry.counter("parcel.delivered", "parcelId", parcelId, "channel", channel).increment();
                    }
                """), "logast.yaml", policy));
        gone(Rules.METRIC_HIGH_CARDINALITY_TAG, "the id tag removed", with(metrics("", "", """
                    public void delivered(String parcelId, String channel) {
                        registry.counter("parcel.delivered", "channel", channel).increment();
                    }
                """), "logast.yaml", policy));
    }

    @Test
    void meterCreatedOnce() throws IOException {
        fires(Rules.METRIC_REGISTERED_PER_CALL, metrics("", "", """
                    public void restocked(List<String> skus) {
                        for (String sku : skus) {
                            registry.counter("parcel.restocked").increment();
                        }
                    }
                """));
        gone(Rules.METRIC_REGISTERED_PER_CALL, "created once in the constructor, kept in a field", metrics(
                "    private final Counter restocked;\n", "        this.restocked = registry.counter(\"parcel.restocked\");\n", """
                            public void restocked(List<String> skus) {
                                for (String sku : skus) {
                                    restocked.increment();
                                }
                            }
                        """));
    }

    @Test
    void sameTagKeysEverywhere() throws IOException {
        String failed = """
                    public void reservationFailed(String channel, String reason) {
                        Counter.builder("parcel.reserved").tag("channel", channel).tag("reason", reason).register(registry).increment();
                    }
                """;
        fires(Rules.METRIC_TAG_KEYS_MISMATCH, metrics("", "", """
                    public void reserved(String channel) {
                        Counter.builder("parcel.reserved").tag("channel", channel).register(registry).increment();
                    }

                """ + failed));
        gone(Rules.METRIC_TAG_KEYS_MISMATCH, "reason=\"none\" on the success counter", metrics("", "", """
                    public void reserved(String channel) {
                        Counter.builder("parcel.reserved").tag("channel", channel).tag("reason", "none").register(registry).increment();
                    }

                """ + failed));
    }

    @Test
    void outcomeTagFromTheVocabulary() throws IOException {
        String policy = PACKS + "metrics:\n  tags:\n    outcome:\n      values: [success, failure]\n";
        fires(Rules.METRIC_TAG_VALUE, with(metrics("", "", """
                    public void delivered() {
                        registry.counter("parcel.delivered", "outcome", "ok").increment();
                    }
                """), "logast.yaml", policy));
        gone(Rules.METRIC_TAG_VALUE, "one of [success, failure]", with(metrics("", "", """
                    public void delivered() {
                        registry.counter("parcel.delivered", "outcome", "success").increment();
                    }
                """), "logast.yaml", policy));
    }

    @Test
    void entrypointTimedByItsObservation() throws IOException {
        String controller = """
                package x;

                import io.micrometer.core.instrument.MeterRegistry;
                import io.micrometer.core.instrument.Timer;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class ManifestController {
                    private final MeterRegistry registry;

                    public ManifestController(MeterRegistry registry) {
                        this.registry = registry;
                    }

                    @GetMapping("/manifests/daily")
                    public String daily() {
                BODY    }
                }
                """;
        fires(Rules.METRIC_DIRECT_REGISTRY, Map.of(java("ManifestController"), controller.replace("BODY", """
                        Timer.Sample sample = Timer.start(registry);
                        try {
                            return "manifest";
                        } finally {
                            sample.stop(registry.timer("manifest.daily"));
                        }
                """)));
        gone(Rules.METRIC_DIRECT_REGISTRY, "the hand-made timer dropped (http.server.requests times it)", Map.of(java("ManifestController"),
                controller.replace("BODY", "        return \"manifest\";\n")));
    }

    @Test
    void loggedOutcomeIsCounted() throws IOException {
        String courier = """
                package x;

                import io.micrometer.core.instrument.Counter;
                import io.micrometer.core.instrument.MeterRegistry;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.stereotype.Component;

                @Component
                public class Courier {
                    private static final Logger log = LoggerFactory.getLogger(Courier.class);
                    private final MeterRegistry registry;

                    public Courier(MeterRegistry registry) {
                        this.registry = registry;
                    }

                    public void deliver(String parcelId) {
                        log.info("Parcel delivered [parcelId={}]", parcelId);
                COUNT    }
                }
                """;
        fires(Rules.METRIC_OUTCOME_UNMEASURED, Map.of(java("Courier"), courier.replace("COUNT", "")));
        gone(Rules.METRIC_OUTCOME_UNMEASURED, "a counter for the outcome", Map.of(java("Courier"), courier.replace("COUNT",
                "        Counter.builder(\"parcel\").tag(\"outcome\", \"delivered\").register(registry).increment();\n")));
        // the starter's Outcomes is not on the fixture's classpath: the call is recognised by its name
        gone(Rules.METRIC_OUTCOME_UNMEASURED, "Outcomes.count(..) after the line", Map.of(java("Courier"), courier.replace("COUNT",
                "        Outcomes.count(\"parcel\", \"delivered\");\n"), java("Outcomes"), """
                package x;

                public final class Outcomes {
                    public static void count(String name, String outcome) {
                    }
                }
                """));
    }

    // ---- ROW_BY_ROW_WRITE ------------------------------------------------------------------------------------------

    @Test
    void oneBatchWriteInsteadOfOnePerRow() throws IOException {
        String job = """
                package x;

                import java.util.ArrayList;
                import java.util.List;
                import org.springframework.stereotype.Component;

                @Component
                public class ParcelPurge {
                    private final ParcelRepository repository;

                    public ParcelPurge(ParcelRepository repository) {
                        this.repository = repository;
                    }

                    public void purge() {
                BODY    }
                }
                """;
        String repository = """
                package x;

                import java.util.Collection;
                import java.util.List;
                import java.util.Map;
                import java.util.concurrent.ConcurrentHashMap;
                import org.springframework.stereotype.Repository;

                @Repository
                public class ParcelRepository {
                    private final Map<String, String> parcels = new ConcurrentHashMap<>();

                    public Collection<String> findAll() {
                        return List.copyOf(parcels.keySet());
                    }

                    public void delete(String parcelId) {
                        parcels.remove(parcelId);
                    }

                    public void deleteAll(Collection<String> parcelIds) {
                        parcelIds.forEach(parcels::remove);
                    }

                    public void deleteEmpty() {
                        parcels.values().removeIf(String::isEmpty);
                    }
                }
                """;
        fires(Rules.ROW_BY_ROW_WRITE, Map.of(java("ParcelPurge"), job.replace("BODY", """
                        for (String parcelId : repository.findAll()) {
                            if (parcelId.startsWith("E")) {
                                repository.delete(parcelId);
                            }
                        }
                """), java("ParcelRepository"), repository));
        gone(Rules.ROW_BY_ROW_WRITE, "collected, then one batch write", Map.of(java("ParcelPurge"), job.replace("BODY", """
                        List<String> empty = new ArrayList<>();
                        for (String parcelId : repository.findAll()) {
                            if (parcelId.startsWith("E")) {
                                empty.add(parcelId);
                            }
                        }
                        repository.deleteAll(empty);
                """), java("ParcelRepository"), repository));
        gone(Rules.ROW_BY_ROW_WRITE, "one bulk statement", Map.of(java("ParcelPurge"), job.replace("BODY", """
                        repository.deleteEmpty();
                """), java("ParcelRepository"), repository));
    }

    // ---- UNBOUNDED_EXECUTOR (Executors factory) --------------------------------------------------------------------

    @Test
    void handMadePoolWithABoundedQueue() throws IOException {
        String jobs = """
                package x;

                import java.util.concurrent.ArrayBlockingQueue;
                import java.util.concurrent.ExecutorService;
                import java.util.concurrent.Executors;
                import java.util.concurrent.ThreadPoolExecutor;
                import java.util.concurrent.TimeUnit;
                import org.springframework.stereotype.Component;

                @Component
                public class ManifestJobs {
                    private final ExecutorService pool = POOL;

                    public void start(Runnable job) {
                        pool.execute(job);
                    }
                }
                """;
        fires(Rules.UNBOUNDED_EXECUTOR, Map.of(java("ManifestJobs"), jobs.replace("POOL", "Executors.newFixedThreadPool(4)")));
        gone(Rules.UNBOUNDED_EXECUTOR, "new ThreadPoolExecutor(.., new ArrayBlockingQueue<>(capacity), CallerRunsPolicy)",
                Map.of(java("ManifestJobs"), jobs.replace("POOL", "new ThreadPoolExecutor(4, 4, 60, TimeUnit.SECONDS,\n"
                        + "            new ArrayBlockingQueue<>(100), new ThreadPoolExecutor.CallerRunsPolicy())")));
    }

    // ---- ASYNC_CONTEXT_LOSS (parallel stream), ASYNC_ERROR_LOST ----------------------------------------------------

    @Test
    void sequentialStreamKeepsTheContext() throws IOException {
        String exporter = """
                package x;

                import java.util.List;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.stereotype.Component;

                @Component
                public class ManifestWriter {
                    private static final Logger log = LoggerFactory.getLogger(ManifestWriter.class);

                    public void write(List<String> manifestIds) {
                        manifestIds.STREAM().forEach(this::writeOne);
                    }

                    private void writeOne(String manifestId) {
                        log.debug("Manifest written");
                    }
                }
                """;
        fires(Rules.ASYNC_CONTEXT_LOSS, Map.of(java("ManifestWriter"), exporter.replace("STREAM", "parallelStream")));
        gone(Rules.ASYNC_CONTEXT_LOSS, "a sequential stream", Map.of(java("ManifestWriter"), exporter.replace("STREAM", "stream")));
    }

    @Test
    void submittedWorkKeepsItsFailure() throws IOException {
        String sweeper = """
                package x;

                import java.util.concurrent.ExecutorService;
                import java.util.concurrent.Future;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.stereotype.Component;

                @Component
                public class CacheSweeper {
                    private static final Logger log = LoggerFactory.getLogger(CacheSweeper.class);
                    private final ExecutorService pool;

                    public CacheSweeper(ExecutorService pool) {
                        this.pool = pool;
                    }

                    public RESULT sweepLater() {
                        CALL;
                    }

                    private void sweep() {
                        log.debug("Cache sweep completed");
                    }
                }
                """;
        fires(Rules.ASYNC_ERROR_LOST, Map.of(java("CacheSweeper"), sweeper.replace("RESULT", "void").replace("CALL", "pool.submit(this::sweep)")));
        gone(Rules.ASYNC_ERROR_LOST, "execute(…) for fire-and-forget work", Map.of(java("CacheSweeper"),
                sweeper.replace("RESULT", "void").replace("CALL", "pool.execute(this::sweep)")));
        gone(Rules.ASYNC_ERROR_LOST, "the Future kept (returned to the caller)", Map.of(java("CacheSweeper"),
                sweeper.replace("RESULT", "Future<?>").replace("CALL", "return pool.submit(this::sweep)")));
    }

    // ---- AREA_MDC_MISSING ------------------------------------------------------------------------------------------

    @Test
    void areaKeySetWhereWorkEnters() throws IOException {
        String policy = PACKS + """
                areas:
                  billing:
                    packages: [x.billing]
                    requiredMdc: [parcelId]
                """;
        String controller = """
                package x;

                import org.slf4j.MDC;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RestController;
                import x.billing.Biller;

                @RestController
                public class ParcelController {
                    private final Biller biller;

                    public ParcelController(Biller biller) {
                        this.biller = biller;
                    }

                    @PostMapping("/parcels/{ref}/bill")
                    public void bill(@PathVariable String ref) {
                BODY    }
                }
                """;
        Map<String, String> before = Map.of("logast.yaml", policy, java("ParcelController"),
                controller.replace("BODY", "        biller.bill(ref, 100);\n"), "src/main/java/x/billing/Biller.java", """
                        package x.billing;

                        import org.slf4j.Logger;
                        import org.slf4j.LoggerFactory;
                        import org.springframework.stereotype.Component;

                        @Component
                        public class Biller {
                            private static final Logger log = LoggerFactory.getLogger(Biller.class);

                            public void bill(String ref, long amountCents) {
                                charge(amountCents);
                            }

                            private void charge(long amountCents) {
                                log.info("Charge completed [amountCents={}]", amountCents);
                            }
                        }
                        """);
        fires(Rules.AREA_MDC_MISSING, before);
        gone(Rules.AREA_MDC_MISSING, "parcelId set once at the entrypoint", with(before, java("ParcelController"), controller.replace("BODY", """
                        try (MDC.MDCCloseable scope = MDC.putCloseable("parcelId", ref)) {
                            biller.bill(ref, 100);
                        }
                """)));
    }

    // ---- TRACING_EXPORT_UNCONFIGURED -------------------------------------------------------------------------------

    /** #89: the OTLP endpoint is per environment: a profile file or the deployment's environment sets it. */
    @Test
    void tracingExportWithAnEndpointPerEnvironment() throws IOException {
        Map<String, String> before = Map.of(java("RemedyApplication"), """
                package x;

                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class RemedyApplication {
                }
                """);
        fires(Rules.TRACING_EXPORT_UNCONFIGURED, before);
        gone(Rules.TRACING_EXPORT_UNCONFIGURED, "the endpoint in a profile's configuration", with(before,
                "src/main/resources/application-prod.yml", """
                        management:
                          opentelemetry:
                            tracing:
                              export:
                                otlp:
                                  endpoint: http://otel-collector:4318/v1/traces
                        """));
        gone(Rules.TRACING_EXPORT_UNCONFIGURED, "OTEL_EXPORTER_OTLP_ENDPOINT in the deployment", with(before,
                "deploy/deployment.yaml", """
                        env:
                          - name: OTEL_EXPORTER_OTLP_ENDPOINT
                            value: http://otel-collector:4318
                        """));
    }

    // ---- TRACE_ID_NOT_LOGGED ---------------------------------------------------------------------------------------

    /** #89: a custom logback pattern with the trace id, or with Spring Boot's correlation pattern, is matched to traces. */
    @Test
    void customLogPatternWithTheTraceId() throws IOException {
        String logback = """
                <configuration>
                    <property name="LINE" value="%%d %%-5level %s%%logger{36} - %%msg%%n"/>
                    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
                        <encoder>
                            <pattern>${LINE}</pattern>
                        </encoder>
                    </appender>
                    <root level="INFO">
                        <appender-ref ref="CONSOLE"/>
                    </root>
                </configuration>
                """;
        Map<String, String> before = Map.of("logast.yaml", "packs: [tracing]\n", java("RemedyApplication"), """
                package x;

                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class RemedyApplication {
                }
                """, "src/main/resources/logback-spring.xml", logback.formatted(""));
        fires(Rules.TRACE_ID_NOT_LOGGED, before);
        gone(Rules.TRACE_ID_NOT_LOGGED, "%X{traceId} in the pattern", with(before,
                "src/main/resources/logback-spring.xml", logback.formatted("[%X{traceId:-},%X{spanId:-}] ")));
        gone(Rules.TRACE_ID_NOT_LOGGED, "Spring Boot's correlation pattern", with(before,
                "src/main/resources/logback-spring.xml", logback.formatted("${LOG_CORRELATION_PATTERN:-}")));

        // no file of its own: Spring Boot's console pattern replaced by a property
        Map<String, String> property = new LinkedHashMap<>(before);
        property.remove("src/main/resources/logback-spring.xml");
        fires(Rules.TRACE_ID_NOT_LOGGED, with(property, "src/main/resources/application.yml", """
                spring:
                  application:
                    name: remedy
                logging:
                  pattern:
                    console: "%d %-5level %logger - %msg%n"
                """));
        gone(Rules.TRACE_ID_NOT_LOGGED, "the correlation pattern in logging.pattern.console", with(property,
                "src/main/resources/application.yml", """
                        spring:
                          application:
                            name: remedy
                        logging:
                          pattern:
                            console: "%d %-5level ${LOG_CORRELATION_PATTERN:-}%logger - %msg%n"
                        """));
    }

    // ---- REQUEST_NOT_VALIDATED / #52 library advice ---------------------------------------------------------------

    private static final Map<String, String> LABEL_REQUEST = Map.of(java("LabelRequest"), """
            package x;

            import jakarta.validation.constraints.NotBlank;

            public record LabelRequest(@NotBlank String labelCode) {
            }
            """);

    private static String labelController(String parameter) {
        return """
                package x;

                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class LabelController {
                    @PostMapping("/labels")
                    public String create(%s LabelRequest request) {
                        return request.labelCode();
                    }
                }
                """.formatted(parameter);
    }

    @Test
    void requestBodyValidatedWithValid() throws IOException {
        Map<String, String> before = with(LABEL_REQUEST, java("LabelController"), labelController("@RequestBody"));
        fires(Rules.REQUEST_NOT_VALIDATED, before);
        gone(Rules.REQUEST_NOT_VALIDATED, "@Valid on the parameter", with(before, java("LabelController"),
                labelController("@jakarta.validation.Valid @RequestBody")));
        gone(Rules.REQUEST_NOT_VALIDATED, "@Validated on the parameter", with(before, java("LabelController"),
                labelController("@org.springframework.validation.annotation.Validated @RequestBody")));
    }

    /** #52: the exception is mapped by a @RestControllerAdvice in a library jar, which the sources do not show. */
    @Test
    void exceptionMappedByAnAdviceInALibrary() throws IOException {
        Map<String, String> before = Map.of(java("LabelController"), """
                package x;

                import org.platformlib.errors.NotFound;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class LabelController {
                    @GetMapping("/labels/{labelCode}")
                    public String label(@PathVariable String labelCode) {
                        throw new LabelMissing(labelCode);
                    }

                    static class LabelMissing extends NotFound {
                        LabelMissing(String labelCode) {
                            super("no label " + labelCode);
                        }
                    }
                }
                """);
        Map<String, String> base = Map.of("org/platformlib/errors/NotFound.java", """
                package org.platformlib.errors;

                public class NotFound extends RuntimeException {
                    public NotFound(String message) {
                        super(message);
                    }
                }
                """);
        Path without = stubJar(base);
        assertThat(RuleLedgerTest.mentioned(scan(before, List.of(without)))).as("no handler maps it").contains(Rules.UNMAPPED_EXCEPTION);
        Files.move(without, dir.resolve("without.jar"));
        deleteTree(dir.resolve("stubs"));
        Path with = stubJar(with(base, "org/platformlib/errors/PlatformErrors.java", """
                package org.platformlib.errors;

                import org.springframework.http.HttpStatus;
                import org.springframework.http.ProblemDetail;
                import org.springframework.web.bind.annotation.ExceptionHandler;
                import org.springframework.web.bind.annotation.RestControllerAdvice;

                @RestControllerAdvice
                public class PlatformErrors {
                    @ExceptionHandler(NotFound.class)
                    ProblemDetail notFound(NotFound e) {
                        return ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
                    }
                }
                """));
        assertThat(RuleLedgerTest.mentioned(scan(before, List.of(with)))).as("the library's advice maps it")
                .doesNotContain(Rules.UNMAPPED_EXCEPTION);
    }

    private static void deleteTree(Path root) throws IOException {
        try (java.util.stream.Stream<Path> s = Files.walk(root)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
