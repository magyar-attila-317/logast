package dev.logast;

import dev.logast.orders.Edit;
import dev.logast.orders.ScanResult;
import dev.logast.orders.WorkOrder;
import dev.logast.policy.Policy;
import dev.logast.rules.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #292, #325: a catch that returns a designed default (a fail-closed check, an unknown code mapped to OTHER, a missing
 * key read as false) is not a failure: its line is DEBUG, without the stack, its wording generated only from a value
 * named in words (DEFAULT_CURRENCY), never from the method; a record gets no logger. A catch that drops the exception
 * and returns nothing keeps the WARN with the exception.
 */
class FallbackWordingTest {
    @Test
    void aDesignedDefaultIsDebugWithoutTheStack(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Rates.java"), """
                package x;

                import java.util.Collections;
                import java.util.List;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                public class Rates {
                    private static final Logger log = LoggerFactory.getLogger(Rates.class);
                    private static final String DEFAULT_CURRENCY = "EUR";
                    private static final String OTHER = "other";

                    String currency(String code) {
                        try {
                            return lookup(code);
                        } catch (IllegalStateException e) {
                            return DEFAULT_CURRENCY;
                        }
                    }

                    boolean validateAccessToken(String token) {
                        try {
                            return lookup(token) != null;
                        } catch (IllegalStateException e) {
                            return false;
                        }
                    }

                    String approver(String code, String approverUuid) {
                        try {
                            return lookup(code);
                        } catch (IllegalStateException e) {
                            return approverUuid;
                        }
                    }

                    String category(String code) {
                        try {
                            return lookup(code);
                        } catch (IllegalArgumentException e) {
                            return OTHER;
                        }
                    }

                    List<String> history(String code) {
                        try {
                            return List.of(lookup(code));
                        } catch (IllegalStateException e) {
                            return Collections.emptyList();
                        }
                    }

                    void refresh(String code) {
                        try {
                            lookup(code);
                        } catch (IllegalStateException e) {
                            cleanup();
                        }
                    }

                    String lookup(String code) {
                        return code;
                    }

                    void cleanup() {
                    }
                }
                """);
        Files.writeString(src.resolve("ThingDto.java"), """
                package x;

                public record ThingDto(String kind) {
                    public ThingDto {
                        try {
                            Kind.valueOf(kind);
                        } catch (IllegalArgumentException e) {
                            kind = "OTHER";
                        }
                    }

                    enum Kind { A, B }
                }
                """);
        ScanResult r = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null)));
        List<WorkOrder> swallowed = r.orders().stream().filter(o -> o.rule().equals(Rules.SWALLOWED_EXCEPTION)).toList();
        Map<String, String> generated = swallowed.stream().filter(o -> o.slots().containsKey("MSG"))
                .collect(Collectors.toMap(WorkOrder::method, o -> String.valueOf(o.slots().get("MSG").generated())));
        assertThat(generated).containsEntry("Rates.currency", "Fell back to default currency")
                .as("no wording from a method name, a literal, a one-word constant or an empty result")
                .containsEntry("Rates.validateAccessToken", "null").containsEntry("Rates.category", "null")
                .containsEntry("Rates.history", "null")
                .as("#329: a variable is no designed default's name").containsEntry("Rates.approver", "null");
        Map<String, String> lines = swallowed.stream().filter(o -> !o.edits().isEmpty()).collect(Collectors.toMap(WorkOrder::method,
                o -> o.edits().stream().map(Edit::text).filter(t -> t != null && t.contains("{{MSG}}")).findFirst().orElse("")));
        for (String designed : List.of("Rates.currency", "Rates.validateAccessToken", "Rates.category", "Rates.history")) {
            assertThat(lines.get(designed)).as(designed + ": DEBUG, the class as a key, no stack")
                    .contains("log.debug(\"{{MSG}}\"").contains("e.getClass().getSimpleName()").doesNotContain(", e);");
        }
        assertThat(lines.get("Rates.refresh")).as("a catch that drops it keeps the WARN with the exception")
                .contains("log.warn(\"{{MSG}}\"").contains(", e);");
        assertThat(swallowed).filteredOn(o -> o.method().startsWith("ThingDto")).singleElement()
                .satisfies(o -> assertThat(o.edits()).as("a record gets no logger").isEmpty());
    }
}
