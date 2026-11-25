package dev.logast;

import dev.logast.orders.ScanResult;
import dev.logast.orders.WorkOrder;
import dev.logast.policy.Policy;
import dev.logast.rules.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** #292: a catch that falls back to a value gets a line saying which, in words from the code, not a text to wait for. */
class FallbackWordingTest {
    @Test
    void aFallbackSaysWhatItFellBackTo(@TempDir Path dir) throws Exception {
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

                    String currency(String code) {
                        try {
                            return lookup(code);
                        } catch (IllegalStateException e) {
                            return DEFAULT_CURRENCY;
                        }
                    }

                    List<String> history(String code) {
                        try {
                            return List.of(lookup(code));
                        } catch (IllegalStateException e) {
                            return Collections.emptyList();
                        }
                    }

                    String raw(String code) {
                        try {
                            return lookup(code);
                        } catch (IllegalStateException e) {
                            return null;
                        }
                    }

                    String lookup(String code) {
                        return code;
                    }
                }
                """);
        ScanResult r = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null)));
        Map<String, String> generated = r.orders().stream().filter(o -> o.rule().equals(Rules.SWALLOWED_EXCEPTION))
                .collect(Collectors.toMap(WorkOrder::method, o -> String.valueOf(o.slots().get("MSG").generated())));
        assertThat(generated).containsEntry("Rates.currency", "Fell back to default currency")
                .containsEntry("Rates.history", "History fell back to an empty list")
                .containsEntry("Rates.raw", "null");
    }
}
