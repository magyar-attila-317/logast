package dev.logast;

import dev.logast.orders.ScanResult;
import dev.logast.policy.Policy;
import dev.logast.rules.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/** #72: the coverage report accounts for every log call the index knows, once, with the reason for each skip. */
class LogLedgerTest {
    @Test
    void everyLogCallOfTheFixtureHasExactlyOneLine() {
        Analysis a = Analysis.run(Fixtures.root(), Fixtures.classpath(), Fixtures.policy());
        ScanResult r = Rules.scan(a);
        List<LogLedger.Entry> entries = a.ledger.entries(a, r.orders(), Map.of());
        assertThat(a.index.logCallCount()).as("the fixture logs").isPositive();
        assertThat(entries).as("one coverage line per log call the index knows").hasSize(a.index.logCallCount());
        assertThat(entries.stream().map(e -> e.file() + ":" + e.offset()).distinct().count()).as("no call twice")
                .isEqualTo(entries.size());
        String text = LogLedger.render(entries, Map.of());
        assertThat(text.lines().filter(l -> !l.startsWith("#")).count()).isEqualTo(a.index.logCallCount());
    }

    @Test
    void eachSkipNamesItsReasonAndOtherApisAreCounted(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("logast.yaml"), "logging:\n  protectedMessages: ['^Payment gateway down']\n");
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Shapes.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                class Shapes {
                    private static final Logger log = LoggerFactory.getLogger(Shapes.class);
                    private static final String TEMPLATE = "Coupon checked [couponId={}]";

                    void run(String couponId, String text, java.util.List<String> items) {
                        log.warn("Payment gateway down [couponId={}]", couponId);
                        log.info(TEMPLATE, couponId);
                        log.info("{}", text);
                        log.atInfo().addKeyValue("couponId", couponId).log("Coupon accepted");
                        log.trace("Coupon items [items={}]", items);
                        log.info("Coupon stored [couponId={}]", couponId);
                        log.info("Coupon {} archived", couponId); // logast:ignore LOG_FORMAT
                    }
                }
                """);
        Files.writeString(src.resolve("LegacyAudit.java"), """
                package x;

                import java.util.logging.Logger;

                class LegacyAudit {
                    private static final Logger log = Logger.getLogger(LegacyAudit.class.getName());

                    void record(String what) {
                        log.info("Audit recorded");
                        log.warning("Audit rejected");
                    }
                }
                """);
        Files.writeString(src.resolve("LogHelper.java"), """
                package x;

                import org.slf4j.Logger;

                class LogHelper {
                    static void failed(Logger log, String what, Exception e) {
                        log.error("Failed: " + what, e);
                    }
                }
                """);
        Analysis a = Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null).withPacks(List.of("logging")));
        ScanResult r = Rules.scan(a);
        List<LogLedger.Entry> entries = a.ledger.entries(a, r.orders(), Map.of());
        assertThat(entries).hasSize(a.index.logCallCount()).hasSize(10);

        Map<String, String> byLine = new TreeMap<>();
        entries.forEach(e -> byLine.put(e.file().replaceAll(".*/", "") + ":" + e.line(), e.outcome() + " " + e.detail()));
        assertThat(byLine.get("Shapes.java:11")).isEqualTo("skipped " + LogLedger.PROTECTED);
        assertThat(byLine.get("Shapes.java:12")).isEqualTo("skipped " + LogLedger.NOT_LITERAL);
        assertThat(byLine.get("Shapes.java:13")).isEqualTo("skipped " + LogLedger.MESSAGE_ARGUMENT);
        assertThat(byLine.get("Shapes.java:14")).isEqualTo("skipped " + LogLedger.FLUENT);
        assertThat(byLine.get("Shapes.java:15")).isEqualTo("skipped " + LogLedger.TRACE);
        assertThat(byLine.get("Shapes.java:16")).isEqualTo("checked no finding");
        assertThat(byLine.get("Shapes.java:17")).isEqualTo("ignored LOG_FORMAT");
        assertThat(byLine.get("LegacyAudit.java:9")).isEqualTo("skipped " + LogLedger.otherApi("java.util.logging"));
        assertThat(byLine.get("LegacyAudit.java:10")).isEqualTo("skipped " + LogLedger.otherApi("java.util.logging"));
        assertThat(byLine.get("LogHelper.java:7")).startsWith("skipped " + LogLedger.HELPER);

        // an order on the line is a finding, and an apply's fix outranks it
        Map<String, TreeMap<Integer, java.util.Set<String>>> fixed = Map.of("src/main/java/x/Shapes.java",
                new TreeMap<>(Map.of(16, new TreeSet<>(List.of("LOG_FORMAT FMT-000000")))));
        assertThat(a.ledger.entries(a, r.orders(), fixed).stream().filter(e -> e.line() == 16 && e.file().endsWith("Shapes.java"))
                .findFirst().orElseThrow().outcome()).isEqualTo("fixed");
    }

    @Test
    void fixedLinesStayWhileTheFileIsUnchanged(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Path file = src.resolve("Jobs.java");
        Files.writeString(file, """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                class Jobs {
                    private static final Logger log = LoggerFactory.getLogger(Jobs.class);

                    void run() {
                        log.info("Coupon job completed");
                    }
                }
                """);
        Policy policy = Policy.resolve(dir, null).withPacks(List.of("logging"));
        Analysis a = Analysis.run(dir, Fixtures.classpath(), policy);
        ScanResult r = Rules.scan(a);
        Map<String, TreeMap<Integer, java.util.Set<String>>> fixed = Map.of("src/main/java/x/Jobs.java",
                new TreeMap<>(Map.of(10, new TreeSet<>(List.of("LOG_FORMAT FMT-123456")))));
        assertThat(a.ledger.write(dir, a, r.orders(), fixed).getFirst().outcome()).isEqualTo("fixed");

        Analysis again = Analysis.run(dir, Fixtures.classpath(), policy);
        assertThat(again.ledger.write(dir, again, Rules.scan(again).orders(), Map.of()).getFirst().detail())
                .as("a later scan keeps it").isEqualTo("LOG_FORMAT FMT-123456");

        Files.writeString(file, Files.readString(file).replace("completed", "finished"));
        Analysis changed = Analysis.run(dir, Fixtures.classpath(), policy);
        assertThat(changed.ledger.write(dir, changed, Rules.scan(changed).orders(), Map.of()).getFirst().outcome())
                .as("the file changed since: no longer claimed").isNotEqualTo("fixed");
    }
}
