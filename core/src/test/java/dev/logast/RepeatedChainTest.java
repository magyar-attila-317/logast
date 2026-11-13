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
}
