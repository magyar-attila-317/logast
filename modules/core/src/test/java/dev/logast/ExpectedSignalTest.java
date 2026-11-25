package dev.logast;

import dev.logast.orders.WorkOrder;
import dev.logast.policy.Policy;
import dev.logast.rules.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** #326 L4: an expected business signal keeps its WARN without the stack; a fault gets the exception attached. */
class ExpectedSignalTest {
    @Test
    void expectedSignalsAreNotAttached(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("ImportConflictException.java"),
                "package x;\n\npublic class ImportConflictException extends RuntimeException {\n}\n");
        Files.writeString(src.resolve("Imports.java"), """
                package x;

                import java.io.IOException;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                public class Imports {
                    private static final Logger log = LoggerFactory.getLogger(Imports.class);

                    enum Code { A, B }

                    void start() {
                        try {
                            run();
                        } catch (ImportConflictException e) {
                            log.warn("Import skipped: another import is running");
                        }
                    }

                    void code(String raw) {
                        try {
                            Code.valueOf(raw);
                        } catch (IllegalArgumentException e) {
                            log.warn("Unknown response code {}", raw);
                        }
                    }

                    void read() {
                        try {
                            load();
                        } catch (IOException e) {
                            log.warn("Read failed");
                        }
                    }

                    void run() {
                    }

                    void load() throws IOException {
                    }
                }
                """);
        List<WorkOrder> orders = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null))).orders();
        Set<String> attached = orders.stream().filter(o -> o.title() != null && o.edits().stream()
                        .anyMatch(e -> e.text() != null && e.text().endsWith(", e")))
                .map(WorkOrder::method).collect(Collectors.toSet());
        assertThat(attached).as("only the fault gets the exception").containsExactly("Imports.read");
    }

    /** #327: a team's own word for an expected outcome (…Fault) is a setting, added to the built-in list. */
    @Test
    void aTeamsOwnExpectedExceptionWordIsASetting(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("PaymentFault.java"), "package x;\n\npublic class PaymentFault extends RuntimeException {\n}\n");
        Files.writeString(src.resolve("Pay.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                public class Pay {
                    private static final Logger log = LoggerFactory.getLogger(Pay.class);

                    void pay() {
                        try {
                            run();
                        } catch (PaymentFault e) {
                            log.warn("Payment refused by the provider");
                        }
                    }

                    void run() {
                    }
                }
                """);
        java.util.function.Supplier<Boolean> attached = () -> {
            try {
                return Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null))).orders().stream()
                        .anyMatch(o -> "Pay.pay".equals(o.method()) && o.edits().stream().anyMatch(e -> e.text() != null && e.text().endsWith(", e")));
            } catch (java.io.IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        };
        assertThat(attached.get()).as("Fault is no built-in word: the exception is attached").isTrue();
        Files.writeString(dir.resolve("logast.yaml"), "levels:\n  expectedExceptions: [Fault]\n");
        assertThat(attached.get()).as("listed: an expected outcome, no stack").isFalse();
        assertThat(Policy.resolve(dir, null).levels().expectedExceptions()).as("added to the built-in list").contains("Fault", "Rejected");
    }

    /** #329: a WARN in a loop's catch that records the item and moves on gets no stack (one per item would be noise). */
    @Test
    void aRecordedItemInALoopGetsNoStack(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Readiness.java"), """
                package x;

                import java.util.ArrayList;
                import java.util.List;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                public class Readiness {
                    private static final Logger log = LoggerFactory.getLogger(Readiness.class);

                    List<String> blockers(List<String> items) {
                        List<String> blockers = new ArrayList<>();
                        for (String item : items) {
                            try {
                                price(item);
                            } catch (IllegalStateException e) {
                                log.warn("Menu item price lookup failed");
                                blockers.add(item);
                            }
                        }
                        return blockers;
                    }

                    void once(String item) {
                        try {
                            price(item);
                        } catch (IllegalStateException e) {
                            log.warn("Menu item price lookup failed");
                        }
                    }

                    void price(String item) {
                    }
                }
                """);
        Set<String> attached = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null))).orders().stream()
                .filter(o -> o.edits().stream().anyMatch(e -> e.text() != null && e.text().endsWith(", e")))
                .map(WorkOrder::method).collect(Collectors.toSet());
        assertThat(attached).as("only the line outside a loop gets the exception").containsExactly("Readiness.once");
    }

    /** #329: a line that names the exception's class and then answers 400 loses the stack; a 500 path keeps it. */
    @Test
    void aClientErrorAnswerDropsTheStack(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Cursor.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.http.HttpStatus;
                import org.springframework.web.server.ResponseStatusException;

                public class Cursor {
                    private static final Logger log = LoggerFactory.getLogger(Cursor.class);

                    String decode(String raw) {
                        try {
                            return parse(raw);
                        } catch (IllegalArgumentException ex) {
                            log.warn("Malformed cursor rejected [errorType={}]", ex.getClass().getSimpleName(), ex);
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad cursor");
                        }
                    }

                    String load(String raw) {
                        try {
                            return parse(raw);
                        } catch (IllegalArgumentException ex) {
                            log.warn("Cursor load failed [errorType={}]", ex.getClass().getSimpleName(), ex);
                            throw new IllegalStateException("load failed", ex);
                        }
                    }

                    String parse(String raw) {
                        return raw;
                    }
                }
                """);
        var orders = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null))).orders();
        String decode = orders.stream().filter(o -> "Cursor.decode".equals(o.method())).flatMap(o -> o.edits().stream())
                .map(e -> String.valueOf(e.text())).filter(t -> t.contains("Malformed") || t.contains("errorType")).findFirst().orElse("");
        assertThat(decode).as("the 400 path loses the stack").isNotEmpty().doesNotEndWith(", ex");
        assertThat(orders.stream().filter(o -> "Cursor.load".equals(o.method())).flatMap(o -> o.edits().stream())
                .map(e -> String.valueOf(e.text())).noneMatch(t -> t.contains("errorType") && !t.endsWith(", ex")))
                .as("the 500 path keeps it (no edit that drops it)").isTrue();
    }
}
