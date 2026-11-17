package dev.logast.rules;

import dev.logast.Analysis;
import dev.logast.Fixtures;
import dev.logast.policy.Policy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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

    /** Writes a project (a logast.yaml with every pack unless the files bring their own) and returns the rules it reports. */
    private Set<String> fired(Map<String, String> files) throws IOException {
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
        return RuleLedgerTest.mentioned(Rules.scan(Analysis.run(root, Fixtures.classpath(), policy)).orders());
    }

    private static Map<String, String> with(Map<String, String> base, String... pathsAndTexts) {
        Map<String, String> out = new LinkedHashMap<>(base);
        for (int i = 0; i < pathsAndTexts.length; i += 2) {
            out.put(pathsAndTexts[i], pathsAndTexts[i + 1]);
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
        stillFires(Rules.BLOCKING_WAIT_UNBOUNDED, "orTimeout(..)", "#58 c: the note suggests orTimeout(..), but the join() "
                        + "after it has no arguments and is reported anyway",
                Map.of(java("QuoteController"), controller.formatted("quote.orTimeout(5, TimeUnit.SECONDS).join()")));
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
        stillFires(Rules.ASYNC_CONTEXT_LOSS, "ContextExecutors.wrap(executor)", "#57: the note names ContextExecutors.wrap(executor) "
                        + "first, but only a Spring TaskExecutor / TaskScheduler argument counts (FactsExtractor.asyncApiManaged)",
                Map.of(java("RefreshController"), controller.formatted(", ContextExecutors.wrap(pool)"), java("ContextExecutors"), wrap));
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
        stillFires(Rules.AUDIT_LOG_MIXED, "the records on their own logger", "#57: the note says to give the records their own "
                        + "logger and keep the class logger for the diagnostics, but the rule counts every log line of the class, "
                        + "whichever logger writes it",
                with(before, java("AuditLog"), auditLog.formatted(
                        "    private static final Logger AUDIT = LoggerFactory.getLogger(\"audit\");\n", "AUDIT")));
    }
}
