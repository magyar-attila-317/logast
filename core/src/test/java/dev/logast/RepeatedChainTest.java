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
                  coupon: { mdc: { key: couponId, sources: { params: [couponId] } } }
                """);
        Files.writeString(src.resolve("Coupons.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                class CouponFacade {
                    private static final Logger log = LoggerFactory.getLogger(CouponFacade.class);
                    private final CouponService service = new CouponService();

                    void handle(String couponId) {
                        log.info("Coupon request received [couponId={}]", couponId);
                        service.publish(couponId);
                    }
                }

                class CouponService {
                    private static final Logger log = LoggerFactory.getLogger(CouponService.class);

                    void publish(String couponId) {
                        log.info("Coupon publication started [couponId={}]", couponId);
                        send(couponId);
                    }

                    private void send(String couponId) {
                        log.info("Coupon sent [couponId={}]", couponId);
                    }
                }
                """);
        ScanResult r = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null)));
        assertThat(r.orders()).filteredOn(o -> o.rule().equals(Rules.REPEATED_LOG_VALUE)).extracting(WorkOrder::method)
                .containsExactly("CouponFacade.handle");                       // not publish, not send
        assertThat(r.orders()).filteredOn(o -> o.rule().equals(Rules.REPEATED_LOG_VALUE)).singleElement()
                .satisfies(o -> assertThat(o.title()).contains("logged by 3 statements", "and the methods it passes it to"));
    }

    @Test
    void messageKeysDoNotLookLikeAssignments(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(dir.resolve("logast.yaml"), """
                logging: { keyStyle: camel }
                vocabulary:
                  coupon: { mdc: { key: couponId, sources: { params: [couponId] } } }
                """);
        Files.writeString(src.resolve("One.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                class One {
                    private static final Logger log = LoggerFactory.getLogger(One.class);

                    void run(String couponId) {
                        log.info("Coupon checked [couponId={}]", couponId);
                        log.info("Coupon published [couponId={}]", couponId);
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
                  coupon: { mdc: { key: couponId, sources: { params: [couponId] } } }
                """);
        Files.writeString(src.resolve("Coupons.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.boot.context.event.ApplicationReadyEvent;
                import org.springframework.context.event.EventListener;

                class CouponListener {
                    private static final Logger log = LoggerFactory.getLogger(CouponListener.class);
                    private final CouponService service = new CouponService();

                    @EventListener(ApplicationReadyEvent.class)
                    public void onReady() {
                        String couponId = service.next();
                        log.info("Coupon picked [couponId={}]", couponId);
                        service.publish(couponId);
                    }
                }

                class CouponService {
                    private static final Logger log = LoggerFactory.getLogger(CouponService.class);

                    String next() { return "c-1"; }

                    void publish(String couponId) {
                        log.info("Coupon publication started [couponId={}]", couponId);
                        log.info("Coupon publication completed [couponId={}]", couponId);
                    }
                }
                """);
        ScanResult r = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null)));
        // the listener gets an observation wrapper (not this rule); the service must still scope couponId itself
        assertThat(r.orders()).filteredOn(o -> o.rule().equals(Rules.REPEATED_LOG_VALUE)).extracting(WorkOrder::method)
                .contains("CouponService.publish");
    }

    @Test
    void valuesInMdcLeaveTheMessagesOfTheWholeFlow(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(dir.resolve("logast.yaml"), """
                logging: { keyStyle: camel }
                vocabulary:
                  coupon: { mdc: { key: couponId, sources: { params: [couponId] } } }
                """);
        Files.writeString(src.resolve("Coupons.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                class CouponService {
                    private static final Logger log = LoggerFactory.getLogger(CouponService.class);
                    private final CouponSender sender = new CouponSender();

                    void publish(String couponId) {
                        log.info("Publishing coupon {}", couponId);
                        sender.send(couponId);
                        log.info("Coupon {} published", couponId);
                    }
                }

                class CouponSender {
                    private static final Logger log = LoggerFactory.getLogger(CouponSender.class);

                    void send(String couponId) {
                        log.debug("Sending coupon {} to the channel", couponId);
                        log.info("Coupon sent, couponId={}", couponId);
                    }
                }
                """);
        ScanResult r = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null)));
        // no entrypoint reaches these methods: the scope in publish still covers send
        assertThat(r.orders()).filteredOn(o -> o.rule().equals(Rules.REPEATED_LOG_VALUE)).extracting(WorkOrder::method)
                .containsExactly("CouponService.publish");
        String edits = String.join("\n", r.orders().stream().filter(WorkOrder::mechanical)
                .flatMap(o -> o.edits().stream()).map(e -> e.text()).toList());
        assertThat(edits).contains("\"Publishing coupon\"", "\"Coupon published\"", "\"Sending coupon to the channel\"", "\"Coupon sent\"");
        assertThat(r.orders()).noneMatch(o -> !o.slots().isEmpty());          // nothing left for a person to word
    }

    @Test
    void aValueRecognisedByItsMessageKeyIsDroppedToo(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(dir.resolve("logast.yaml"), """
                logging: { keyStyle: camel }
                vocabulary:
                  coupon: { mdc: { key: couponId, sources: { params: [couponId] } } }
                """);
        Files.writeString(src.resolve("Coupons.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                record CouponRequest(String code) {
                }

                class CouponService {
                    private static final Logger log = LoggerFactory.getLogger(CouponService.class);

                    void publish(CouponRequest request) {
                        log.info("Coupon publication started [couponId={}]", request.code());
                        log.info("Coupon publication completed [couponId={}]", request.code());
                    }
                }
                """);
        ScanResult r = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null)));
        assertThat(r.orders()).filteredOn(o -> o.rule().equals(Rules.REPEATED_LOG_VALUE)).extracting(WorkOrder::method)
                .containsExactly("CouponService.publish");
        // the same recognition drops it from the messages: no line keeps [couponId={}] under the new scope
        String edits = String.join("\n", r.orders().stream().filter(o -> o.rule().equals(Rules.LOG_FORMAT) || o.fixes().contains(Rules.LOG_FORMAT))
                .flatMap(o -> o.edits().stream()).map(e -> e.text()).toList());
        assertThat(edits).contains("\"Coupon publication started\"", "\"Coupon publication completed\"").doesNotContain("couponId={}");
    }
}
