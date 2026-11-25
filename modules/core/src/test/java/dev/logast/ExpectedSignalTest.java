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
}
