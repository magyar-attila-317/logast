package dev.logast;

import dev.logast.orders.ScanResult;
import dev.logast.orders.WorkOrder;
import dev.logast.policy.Policy;
import dev.logast.rules.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** #290: code that withholds an exception on purpose (a comment says so, a redacting helper) is reported, not changed. */
class DeliberateCodeTest {
    @Test
    void withheldOnPurposeIsLeftAlone(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("GatewayException.java"), """
                package x;
                public class GatewayException extends RuntimeException {
                    public GatewayException(String message) { super(message); }
                    public GatewayException(String message, Throwable cause) { super(message, cause); }
                }
                """);
        Files.writeString(src.resolve("Gateway.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                public class Gateway {
                    private static final Logger log = LoggerFactory.getLogger(Gateway.class);

                    String redact(Exception e) { return e.getClass().getSimpleName(); }

                    void plain() {
                        try { call(); } catch (IllegalStateException e) {
                            throw new GatewayException("Call failed");
                        }
                    }

                    void commented() {
                        try { call(); } catch (IllegalStateException e) {
                            // deliberately no cause detail: the response body holds card data
                            throw new GatewayException("Call failed");
                        }
                    }

                    void redacted() {
                        try { call(); } catch (IllegalStateException e) {
                            throw new GatewayException("Call failed: " + redact(e));
                        }
                    }

                    void logPlain() {
                        try { call(); } catch (IllegalStateException e) {
                            log.warn("Decrypt failed");
                        }
                    }

                    void logCommented() {
                        try { call(); } catch (IllegalStateException e) {
                            // do not log the exception: it carries the key material
                            log.warn("Decrypt failed");
                        }
                    }

                    void call() { }
                }
                """);
        ScanResult r = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null)));
        List<WorkOrder> cause = r.orders().stream().filter(o -> o.rule().equals(Rules.CAUSE_DROPPED)).toList();
        assertThat(cause).filteredOn(o -> o.method().equals("Gateway.plain")).singleElement()
                .satisfies(o -> assertThat(o.edits()).as("an unmarked catch gets the cause").isNotEmpty());
        assertThat(cause).filteredOn(o -> o.method().equals("Gateway.commented")).singleElement()
                .satisfies(o -> assertThat(o.edits()).as("a comment says it is on purpose").isEmpty());
        assertThat(cause).filteredOn(o -> o.method().equals("Gateway.redacted")).singleElement()
                .satisfies(o -> assertThat(o.edits()).as("a redacting helper builds the message").isEmpty());
        List<String> lost = r.orders().stream().filter(o -> o.fixes().contains(Rules.LOST_THROWABLE)).map(WorkOrder::method).toList();
        assertThat(lost).contains("Gateway.logPlain").doesNotContain("Gateway.logCommented");
    }
}
