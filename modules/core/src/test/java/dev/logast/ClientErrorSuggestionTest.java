package dev.logast;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** #279: setup offers the service's own 4xx exceptions as levels.clientErrors, commented, for the owner to confirm. */
class ClientErrorSuggestionTest {
    @Test
    void exceptionsAnsweringWithA4xxAreOffered(@TempDir Path root) throws Exception {
        Path pkg = Files.createDirectories(root.resolve("src/main/java/com/example/shop"));
        Files.writeString(pkg.resolve("SlotTakenException.java"), """
                package com.example.shop;

                import org.springframework.http.HttpStatus;
                import org.springframework.web.bind.annotation.ResponseStatus;

                @ResponseStatus(HttpStatus.CONFLICT)
                public class SlotTakenException extends RuntimeException {
                }
                """);
        Files.writeString(pkg.resolve("LedgerDownException.java"), """
                package com.example.shop;

                import org.springframework.http.HttpStatus;
                import org.springframework.web.bind.annotation.ResponseStatus;

                @ResponseStatus(code = HttpStatus.SERVICE_UNAVAILABLE)
                public class LedgerDownException extends RuntimeException {
                }
                """);
        Files.writeString(pkg.resolve("Answer.java"), """
                package com.example.shop;

                import org.springframework.http.HttpStatus;
                import org.springframework.web.bind.annotation.ResponseStatus;

                @ResponseStatus(HttpStatus.NOT_FOUND)
                public class Answer {
                }
                """);
        String template = ServiceSetup.policyTemplate(List.of(), List.of("logging"), false, null);
        String policy = ServiceSetup.suggestClientErrors(template, root);
        assertThat(policy).contains("#   clientErrors: [com.example.shop.SlotTakenException]   # found: @ResponseStatus 4xx in the code")
                .doesNotContain("LedgerDownException").doesNotContain("shop.Answer");
        assertThat(ServiceSetup.suggestClientErrors(template, root.resolve("empty"))).as("nothing found: the example stays")
                .isEqualTo(template);
    }
}
