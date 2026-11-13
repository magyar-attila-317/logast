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
}
