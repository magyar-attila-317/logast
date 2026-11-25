package dev.logast;

import dev.logast.orders.Edit;
import dev.logast.orders.WorkOrder;
import dev.logast.policy.Policy;
import dev.logast.rules.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a service's tests caught after the logging pack (0.5.2): a scope that reads a value before the code's own null
 * guard (an NPE instead of the domain exception), lists of plain values reduced to their size, a WARN carrying an id
 * deleted as "logged and rethrown", and a "completed" line on a startup check.
 */
class AppliedLoggingSafetyTest {
    private static final String VOCABULARY = """
            vocabulary:
              table:
                mdc: { key: tableId }
              tenant:
                mdc: { key: tenantId, sources: { params: [tenantId] } }
            """;

    @Test
    void aScopeNeverReadsAValueBeforeItsNullGuard(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("logast.yaml"), VOCABULARY);
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Table.java"), "package x;\n\npublic record Table(String tableId) {\n    public String getTableId() { return tableId; }\n}\n");
        Files.writeString(src.resolve("Seat.java"), "package x;\n\npublic record Seat(Table table) {\n    public Table getTable() { return table; }\n}\n");
        Files.writeString(src.resolve("Seating.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                public class Seating {
                    private static final Logger log = LoggerFactory.getLogger(Seating.class);

                    void seat(Seat seat) {
                        if (seat.getTable() == null) {
                            throw new IllegalStateException("no table");
                        }
                        log.info("Seat checked [tableId={}]", seat.getTable().getTableId());
                        log.info("Seat booked [tableId={}]", seat.getTable().getTableId());
                        log.info("Seat confirmed [tableId={}]", seat.getTable().getTableId());
                    }
                }
                """);
        List<WorkOrder> orders = scan(dir);
        String edits = edits(orders, "Seating.seat");
        assertThat(edits).as("no scope evaluates seat.getTable().getTableId() before the guard")
                .doesNotContain("MdcScope.of(\"tableId\", seat.getTable().getTableId())");
    }

    @Test
    void listsOfPlainValuesKeepTheirContent(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Access.java"), """
                package x;

                import java.util.List;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                public class Access {
                    private static final Logger log = LoggerFactory.getLogger(Access.class);

                    void refuse(List<String> missing, List<Integer> rates, List<String> ids) {
                        log.warn("Access refused [missing={}]", missing);
                        log.warn("Fee rates mixed [rates={}]", rates);
                        log.warn("Divergence: first {}", ids.stream().limit(5).map(i -> i + "!").toList());
                    }
                }
                """);
        assertThat(scan(dir)).as("plain values and a capped stream are the information")
                .noneMatch(o -> o.rule().equals(Rules.LOG_COLLECTION_ARGUMENT));
    }

    @Test
    void aWarnCarryingAnIdBeforeARethrowStays(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("logast.yaml"), VOCABULARY);
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Lookup.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.slf4j.MDC;

                public class Lookup {
                    private static final Logger log = LoggerFactory.getLogger(Lookup.class);

                    void load(String tenantId) {
                        try (var c = MDC.putCloseable("tenantId", tenantId)) {
                            try {
                                call();
                            } catch (IllegalStateException e) {
                                log.warn("Lookup unavailable [tenantId={}, errorType={}]", tenantId, e.getClass().getSimpleName());
                                throw e;
                            }
                        }
                    }

                    void call() {
                    }
                }
                """);
        Files.writeString(src.resolve("LookupJob.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;

                @Component
                public class LookupJob {
                    private static final Logger log = LoggerFactory.getLogger(LookupJob.class);
                    private final Lookup lookup = new Lookup();

                    @Scheduled(cron = "0 0 * * * *")
                    public void run() {
                        try {
                            lookup.load("t1");
                        } catch (IllegalStateException e) {
                            log.error("Lookup failed", e);
                        }
                    }
                }
                """);
        List<WorkOrder> orders = scan(dir);
        assertThat(orders).as("the caller logs the exception: the rule has a case").anyMatch(o -> "Lookup.load".equals(o.method())
                && (o.rule().equals(Rules.LOG_AND_RETHROW) || o.rule().equals(Rules.EXCEPTION_LOGGED_TWICE)));
        assertThat(orders).filteredOn(o -> "Lookup.load".equals(o.method())
                        && (o.rule().equals(Rules.LOG_AND_RETHROW) || o.rule().equals(Rules.EXCEPTION_LOGGED_TWICE)))
                .allSatisfy(o -> assertThat(o.edits()).as("the tenant goes with the line: not deleted").isEmpty());
    }

    @Test
    void aStartupCheckGetsNoCompletedLine(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("SubmitCheck.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.boot.context.event.ApplicationReadyEvent;
                import org.springframework.context.event.EventListener;
                import org.springframework.stereotype.Component;

                @Component
                public class SubmitCheck {
                    private static final Logger log = LoggerFactory.getLogger(SubmitCheck.class);
                    private boolean auto;

                    @EventListener(ApplicationReadyEvent.class)
                    public void warnIfAutoSubmit() {
                        if (auto) {
                            log.warn("Auto submit is on");
                        }
                    }
                }
                """);
        assertThat(edits(scan(dir), "SubmitCheck.warnIfAutoSubmit")).doesNotContain("completed");
    }

    /** A pipeline is named after what it starts from: "openOrderIds", never "toList". */
    @Test
    void aPipelineIsNamedAfterItsSource(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Order.java"), "package x;\n\npublic record Order(java.util.UUID uuid) {\n    public java.util.UUID getUuid() { return uuid; }\n}\n");
        Files.writeString(src.resolve("Shift.java"), """
                package x;

                import java.util.List;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                public class Shift {
                    private static final Logger log = LoggerFactory.getLogger(Shift.class);

                    void close(List<Order> openOrders) {
                        log.warn("Shift closed with open orders {}", openOrders.stream().map(Order::getUuid).toList());
                    }
                }
                """);
        String edits = edits(scan(dir), "Shift.close");
        assertThat(edits).doesNotContain("toList=").doesNotContain("openOrders.size()").contains("openOrderIds");
    }

    /** D1b: a body that is one try with catches: the scope opens inside it, so reading the value stays covered. */
    @Test
    void aScopeOpensInsideTheCodesOwnTry(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("logast.yaml"), """
                vocabulary:
                  thing:
                    mdc: { key: thingId, sources: { params: [thingId] } }
                """);
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        String text = """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                public class Broadcaster {
                    private static final Logger log = LoggerFactory.getLogger(Broadcaster.class);

                    void broadcast(String thingId) {
                        try {
                            log.debug("Broadcast started [thingId={}]", thingId);
                            log.debug("Broadcast sent [thingId={}]", thingId);
                            log.debug("Broadcast done [thingId={}]", thingId);
                        } catch (RuntimeException ex) {
                            log.error("Broadcast failed [thingId={}]", thingId, ex);
                        }
                    }
                }
                """;
        Files.writeString(src.resolve("Broadcaster.java"), text);
        WorkOrder o = scan(dir).stream().filter(w -> "Broadcaster.broadcast".equals(w.method())
                && w.edits().stream().anyMatch(e -> e.text() != null && e.text().contains("MdcScope.of"))).findFirst().orElseThrow();
        String after = text;
        for (Edit e : o.edits().stream().filter(e -> e.file().endsWith("Broadcaster.java"))
                .sorted((x, y) -> Integer.compare(y.start(), x.start())).toList()) {
            after = after.substring(0, e.start()) + e.text() + after.substring(e.end());
        }
        assertThat(after.indexOf("try {")).as("the code's own try comes first").isLessThan(after.indexOf("MdcScope.of"));
        assertThat(String.join("\n", scan(dir).stream().filter(w -> "Broadcaster.broadcast".equals(w.method()))
                .flatMap(w -> w.edits().stream()).map(Edit::text).filter(java.util.Objects::nonNull).toList()))
                .as("the catch is outside the scope: its line keeps the id").doesNotContain("\"Broadcast failed\", ex");
    }

    /** G4: a rejection a loop records and skips is the expected outcome: no WARN with a stack trace per item. */
    @Test
    void aRecordedRejectionInALoopGetsNoLine(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Importer.java"), """
                package x;

                import java.util.ArrayList;
                import java.util.List;

                public class Importer {
                    private final List<String> skipped = new ArrayList<>();

                    void load(List<String> rows) {
                        for (String row : rows) {
                            try {
                                parse(row);
                            } catch (IllegalArgumentException rejection) {
                                skipped.add(row);
                                continue;
                            }
                            store(row);
                        }
                    }

                    void parse(String row) {
                    }

                    void store(String row) {
                    }
                }
                """);
        assertThat(scan(dir)).filteredOn(o -> "Importer.load".equals(o.method()) && o.rule().equals(Rules.SWALLOWED_EXCEPTION))
                .allSatisfy(o -> assertThat(o.edits()).as("recorded and skipped: no line").isEmpty());
    }

    /**
     * #325 / #326 L3: a loop whose body is the try itself, recording the item in a list; a loop that counts the dropped
     * row (dropped++) and moves on: both are expected outcomes, no WARN line.
     */
    @Test
    void aCheckedOrCountedItemInALoopGetsNoLine(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Readiness.java"), """
                package x;

                import java.util.ArrayList;
                import java.util.List;

                public class Readiness {
                    List<String> blockers(List<String> items) {
                        List<String> blockers = new ArrayList<>();
                        for (String item : items)
                            try {
                                price(item);
                            } catch (IllegalStateException e) {
                                blockers.add(item);
                            }
                        return blockers;
                    }

                    int load(List<String> lines) {
                        int dropped = 0;
                        String firstReason = null;
                        for (String line : lines) {
                            try {
                                price(line);
                            } catch (IllegalArgumentException rejection) {
                                dropped++;
                                firstReason = firstReason == null ? rejection.getMessage() : firstReason;
                                continue;
                            }
                            store(line);
                        }
                        return dropped;
                    }

                    void price(String item) {
                    }

                    void store(String line) {
                    }
                }
                """);
        assertThat(scan(dir)).filteredOn(o -> o.rule().equals(Rules.SWALLOWED_EXCEPTION) && o.method().startsWith("Readiness."))
                .hasSize(2).allSatisfy(o -> assertThat(o.edits()).as(o.method() + ": recorded and moved on: no line").isEmpty());
    }

    /** #323 H2: the same in a forEach lambda: the lambda's end (or a return) moves on to the next row. */
    @Test
    void aRecordedRejectionInAForEachLambdaGetsNoLine(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Report.java"), "package x;\n\npublic class Report {\n    void skipped(String row, String why) { }\n}\n");
        Files.writeString(src.resolve("RecipeImport.java"), """
                package x;

                import java.util.List;

                public class RecipeImport {
                    private final Report report = new Report();

                    void lines(List<String> rows) {
                        rows.forEach(row -> {
                            try {
                                parse(row);
                            } catch (IllegalArgumentException rejection) {
                                report.skipped(row, rejection.getMessage());
                            }
                        });
                    }

                    void assignments(List<String> rows) {
                        rows.forEach(row -> {
                            try {
                                parse(row);
                            } catch (IllegalArgumentException rejection) {
                                report.skipped(row, rejection.getMessage());
                                return;
                            }
                            store(row);
                        });
                    }

                    void once(String row) {
                        try {
                            parse(row);
                        } catch (IllegalArgumentException rejection) {
                            report.skipped(row, rejection.getMessage());
                        }
                    }

                    void parse(String row) {
                    }

                    void store(String row) {
                    }
                }
                """);
        var orders = scan(dir).stream().filter(o -> o.rule().equals(Rules.SWALLOWED_EXCEPTION)).toList();
        assertThat(orders).filteredOn(o -> o.method().equals("RecipeImport.lines") || o.method().equals("RecipeImport.assignments"))
                .hasSize(2).allSatisfy(o -> assertThat(o.edits()).as(o.method() + ": recorded and skipped: no line").isEmpty());
        assertThat(orders).filteredOn(o -> o.method().equals("RecipeImport.once")).singleElement()
                .satisfies(o -> assertThat(o.edits()).as("not in a loop: the line is added").isNotEmpty());
    }

    /** A noun left at the end of a sentence when its id moves to MDC goes with its preposition ("… for tenant"). */
    @Test
    void aTrailingNounGoesWithItsId(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("logast.yaml"), VOCABULARY);
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Lookup.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.slf4j.MDC;

                public class Lookup {
                    private static final Logger log = LoggerFactory.getLogger(Lookup.class);

                    void load(String tenantId) {
                        try (var c = MDC.putCloseable("tenantId", tenantId)) {
                            try {
                                call();
                            } catch (IllegalStateException e) {
                                log.warn("Lookup unavailable for tenant {}: {}", tenantId, e.getClass().getSimpleName());
                            }
                        }
                    }

                    void call() {
                    }
                }
                """);
        String edits = edits(scan(dir), "Lookup.load");
        assertThat(edits).contains("Lookup unavailable").doesNotContain("for tenant [").doesNotContain("for tenant\"");
    }

    private static List<WorkOrder> scan(Path dir) throws Exception {
        return Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null))).orders();
    }

    private static String edits(List<WorkOrder> orders, String method) {
        return String.join("\n", orders.stream().filter(o -> method.equals(o.method())).flatMap(o -> o.edits().stream())
                .map(Edit::text).map(t -> t == null ? "" : t).toList());
    }
}
