package dev.logast;

import dev.logast.orders.ScanResult;
import dev.logast.orders.WorkOrder;
import dev.logast.policy.Policy;
import dev.logast.rules.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** REPEATED_LOG_VALUE along the call chain: MDC once, in the highest method that has the value. */
class RepeatedChainTest {
    @Test
    void mdcGoesToTheHighestMethodThatHasTheValue(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(dir.resolve("logast.yaml"), """
                logging: { keyStyle: camel }
                vocabulary:
                  pledge: { mdc: { key: pledgeId, sources: { params: [pledgeId] } } }
                """);
        Files.writeString(src.resolve("Pledges.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                class PledgeFacade {
                    private static final Logger log = LoggerFactory.getLogger(PledgeFacade.class);
                    private final PledgeService service = new PledgeService();

                    void handle(String pledgeId) {
                        log.info("Pledge request received [pledgeId={}]", pledgeId);
                        service.publish(pledgeId);
                    }
                }

                class PledgeService {
                    private static final Logger log = LoggerFactory.getLogger(PledgeService.class);

                    void publish(String pledgeId) {
                        log.info("Pledge publication started [pledgeId={}]", pledgeId);
                        send(pledgeId);
                    }

                    private void send(String pledgeId) {
                        log.info("Pledge sent [pledgeId={}]", pledgeId);
                    }
                }
                """);
        ScanResult r = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null)));
        assertThat(r.orders()).filteredOn(o -> o.rule().equals(Rules.REPEATED_LOG_VALUE)).extracting(WorkOrder::method)
                .containsExactly("PledgeFacade.handle");                       // not publish, not send
        assertThat(r.orders()).filteredOn(o -> o.rule().equals(Rules.REPEATED_LOG_VALUE)).singleElement()
                .satisfies(o -> assertThat(o.title()).contains("logged by 3 statements", "and the methods it passes it to"));
    }

    @Test
    void messageKeysDoNotLookLikeAssignments(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(dir.resolve("logast.yaml"), """
                logging: { keyStyle: camel }
                vocabulary:
                  pledge: { mdc: { key: pledgeId, sources: { params: [pledgeId] } } }
                """);
        Files.writeString(src.resolve("One.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                class One {
                    private static final Logger log = LoggerFactory.getLogger(One.class);

                    void run(String pledgeId) {
                        log.info("Pledge checked [pledgeId={}]", pledgeId);
                        log.info("Pledge published [pledgeId={}]", pledgeId);
                    }
                }
                """);
        ScanResult r = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null)));
        assertThat(r.orders()).filteredOn(o -> o.rule().equals(Rules.REPEATED_LOG_VALUE)).extracting(WorkOrder::method)
                .containsExactly("One.run");
    }

    @Test
    void aWrappedListenerDoesNotSwallowTheScope(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(dir.resolve("logast.yaml"), """
                logging: { keyStyle: camel }
                vocabulary:
                  pledge: { mdc: { key: pledgeId, sources: { params: [pledgeId] } } }
                """);
        Files.writeString(src.resolve("Pledges.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.boot.context.event.ApplicationReadyEvent;
                import org.springframework.context.event.EventListener;

                class PledgeListener {
                    private static final Logger log = LoggerFactory.getLogger(PledgeListener.class);
                    private final PledgeService service = new PledgeService();

                    @EventListener(ApplicationReadyEvent.class)
                    public void onReady() {
                        String pledgeId = service.next();
                        log.info("Pledge picked [pledgeId={}]", pledgeId);
                        service.publish(pledgeId);
                    }
                }

                class PledgeService {
                    private static final Logger log = LoggerFactory.getLogger(PledgeService.class);

                    String next() { return "c-1"; }

                    void publish(String pledgeId) {
                        log.info("Pledge publication started [pledgeId={}]", pledgeId);
                        log.info("Pledge publication completed [pledgeId={}]", pledgeId);
                    }
                }
                """);
        ScanResult r = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null)));
        // the listener gets an observation wrapper (not this rule); the service must still scope pledgeId itself
        assertThat(r.orders()).filteredOn(o -> o.rule().equals(Rules.REPEATED_LOG_VALUE)).extracting(WorkOrder::method)
                .contains("PledgeService.publish");
    }

    @Test
    void valuesInMdcLeaveTheMessagesOfTheWholeFlow(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(dir.resolve("logast.yaml"), """
                logging: { keyStyle: camel }
                vocabulary:
                  pledge: { mdc: { key: pledgeId, sources: { params: [pledgeId] } } }
                """);
        Files.writeString(src.resolve("Pledges.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                class PledgeService {
                    private static final Logger log = LoggerFactory.getLogger(PledgeService.class);
                    private final PledgeSender sender = new PledgeSender();

                    void publish(String pledgeId) {
                        log.info("Publishing pledge {}", pledgeId);
                        sender.send(pledgeId);
                        log.info("Pledge {} published", pledgeId);
                    }
                }

                class PledgeSender {
                    private static final Logger log = LoggerFactory.getLogger(PledgeSender.class);

                    void send(String pledgeId) {
                        log.debug("Sending pledge {} to the channel", pledgeId);
                        log.info("Pledge sent, pledgeId={}", pledgeId);
                    }
                }
                """);
        ScanResult r = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null)));
        // no entrypoint reaches these methods: the scope in publish still covers send
        assertThat(r.orders()).filteredOn(o -> o.rule().equals(Rules.REPEATED_LOG_VALUE)).extracting(WorkOrder::method)
                .containsExactly("PledgeService.publish");
        String edits = String.join("\n", r.orders().stream().filter(WorkOrder::mechanical)
                .flatMap(o -> o.edits().stream()).map(e -> e.text()).toList());
        assertThat(edits).contains("\"Publishing pledge\"", "\"Pledge published\"", "\"Sending pledge to the channel\"", "\"Pledge sent\"");
        assertThat(r.orders()).noneMatch(o -> !o.slots().isEmpty());          // nothing left for a person to word
    }

    @Test
    void aValueRecognisedByItsMessageKeyIsDroppedToo(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(dir.resolve("logast.yaml"), """
                logging: { keyStyle: camel }
                vocabulary:
                  pledge: { mdc: { key: pledgeId, sources: { params: [pledgeId] } } }
                """);
        Files.writeString(src.resolve("Pledges.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                record PledgeRequest(String code) {
                }

                class PledgeService {
                    private static final Logger log = LoggerFactory.getLogger(PledgeService.class);

                    void publish(PledgeRequest request) {
                        log.info("Pledge publication started [pledgeId={}]", request.code());
                        log.info("Pledge publication completed [pledgeId={}]", request.code());
                    }
                }
                """);
        ScanResult r = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null)));
        assertThat(r.orders()).filteredOn(o -> o.rule().equals(Rules.REPEATED_LOG_VALUE)).extracting(WorkOrder::method)
                .containsExactly("PledgeService.publish");
        // the same recognition drops it from the messages: no line keeps [pledgeId={}] under the new scope
        String edits = String.join("\n", r.orders().stream().filter(o -> o.rule().equals(Rules.LOG_FORMAT) || o.fixes().contains(Rules.LOG_FORMAT))
                .flatMap(o -> o.edits().stream()).map(e -> e.text()).toList());
        assertThat(edits).contains("\"Pledge publication started\"", "\"Pledge publication completed\"").doesNotContain("pledgeId={}");
    }

    private static ScanResult scanStatic(Path dir, String method3Body) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(dir.resolve("logast.yaml"), """
                logging: { keyStyle: camel }
                vocabulary:
                  pledge: { mdc: { key: pledgeId, sources: { params: [pledgeId] } } }
                """);
        Files.writeString(src.resolve("Pledges.java"), """
                package x;

                import java.util.List;
                import dev.logast.support.MdcScope;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                public class Pledges {
                    private static final Logger log = LoggerFactory.getLogger(Pledges.class);

                    public static Object method(String pledgeId) {
                        log.info("Checking pledge [pledgeId={}]", pledgeId);
                        log.info("Pledge accepted [pledgeId={}]", pledgeId);
                        return method2();
                    }

                    private static Object method2() {
                        for (String x : List.of("a", "b")) {
                            method3();
                        }
                        return "ok";
                    }

                    private static Object method3() {
                """ + method3Body + """
                    }

                    private static String current() {
                        return "c-1";
                    }
                }
                """);
        return Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null)));
    }

    @Test
    void aScopeTwoCallsUpCoversTheMethodBelow(@TempDir Path dir) throws Exception {
        ScanResult r = scanStatic(dir, """
                        String pledgeId = current();
                        log.info("Pledge matched [pledgeId={}]", pledgeId);
                        log.info("Pledge recorded [pledgeId={}]", pledgeId);
                        return "result";
                """);
        assertThat(r.orders()).filteredOn(o -> o.rule().equals(Rules.REPEATED_LOG_VALUE)).extracting(WorkOrder::method)
                .containsExactly("Pledges.method");
    }

    /** A scope below no longer blocks the higher place: it becomes nested, and MDC_NESTED_KEY unwraps it next round. */
    @Test
    void aScopeBelowDoesNotBlockTheHigherPlace(@TempDir Path dir) throws Exception {
        ScanResult r = scanStatic(dir, """
                        try (var mdc = MdcScope.of("pledgeId", current())) {
                            log.info("Pledge matched");
                            return "result";
                        }
                """);
        assertThat(r.orders()).filteredOn(o -> o.rule().equals(Rules.REPEATED_LOG_VALUE)).extracting(WorkOrder::method)
                .containsExactly("Pledges.method");
    }

    @Test
    void aRecordAccessorAtTheTopOfTheFlowGetsTheScope(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(dir.resolve("logast.yaml"), """
                logging: { keyStyle: camel }
                vocabulary:
                  pledge: { mdc: { key: pledgeId, sources: { params: [pledgeId] } } }
                """);
        Files.writeString(src.resolve("Pledges.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.web.bind.annotation.*;

                record PledgeRequest(String pledgeId, int amount) {
                }

                @RestController
                public class Pledges {
                    private static final Logger log = LoggerFactory.getLogger(Pledges.class);

                    @PostMapping("/pledges")
                    public Object method0(@RequestBody PledgeRequest request) {
                        log.info("Pledge request received {}", request.pledgeId());
                        return method1(request.pledgeId());
                    }

                    private static Object method1(String pledgeId) {
                        log.info("Checking pledge {}", pledgeId);
                        log.info("Pledge {} accepted", pledgeId);
                        return "ok";
                    }
                }
                """);
        ScanResult r = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null)));
        // request.pledgeId() is a pledgeId (accessor name): the entrypoint's own MDC scope (#15) takes it from the request,
        // so the repeated value needs no scope of its own in method1
        assertThat(r.orders()).filteredOn(o -> o.rule().equals(Rules.MISSING_DOMAIN_MDC)).extracting(WorkOrder::method)
                .containsExactly("Pledges.method0");
        assertThat(r.orders()).filteredOn(o -> o.rule().equals(Rules.REPEATED_LOG_VALUE)).extracting(WorkOrder::method)
                .doesNotContain("Pledges.method1");
    }
}
