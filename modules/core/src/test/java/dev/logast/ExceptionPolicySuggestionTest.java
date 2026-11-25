package dev.logast;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** #290: the first logast.yaml follows how the code already logs a caught exception. */
class ExceptionPolicySuggestionTest {
    private static final String TEMPLATE = ServiceSetup.policyTemplate(List.of(), List.of("logging"), false, null);

    @Test
    void aServiceThatLogsTheClassOnlyGetsThatPolicy(@TempDir Path root) throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            body.append("""
                    void call%d() {
                        try { run(); } catch (IllegalStateException e) {
                            log.warn("Call failed [errorType={}]", e.getClass().getSimpleName());
                        }
                    }
                    """.formatted(i));
        }
        body.append("""
                void last() {
                    try { run(); } catch (RuntimeException ex) {
                        log.error("Last failed", ex);
                    }
                }
                """);
        write(root, body.toString());
        String policy = ServiceSetup.suggestExceptionPolicy(TEMPLATE, root);
        assertThat(policy).contains("  exceptionStack: never     # found: 5 of 6 log lines in catch blocks pass no exception\n")
                .contains("  exceptionMessage: never   # found: 5 of 6 log lines in catch blocks log its class, 0 its message\n");
    }

    @Test
    void aServiceThatAttachesTheExceptionKeepsTheDefaults(@TempDir Path root) throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            body.append("""
                    void call%d() {
                        try { run(); } catch (IllegalStateException e) {
                            log.warn("Call failed: {}", e.getMessage(), e);
                        }
                    }
                    """.formatted(i));
        }
        write(root, body.toString());
        String policy = ServiceSetup.suggestExceptionPolicy(TEMPLATE, root);
        assertThat(policy).contains("  # exceptionStack: never\n  # exceptionMessage: never\n")
                .doesNotContain("  exceptionStack: never");
    }

    private static void write(Path root, String methods) throws Exception {
        Path dir = Files.createDirectories(root.resolve("src/main/java/x"));
        Files.writeString(dir.resolve("Gateway.java"), "package x;\nclass Gateway {\n" + methods + "void run() { }\n}\n");
    }
}
