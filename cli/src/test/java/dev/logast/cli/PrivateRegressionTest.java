package dev.logast.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** #10: the private regression harness itself, on a tiny service that lives only in a temp folder. */
class PrivateRegressionTest {
    @TempDir
    Path tmp;

    private Path service() throws IOException {
        Path svc = tmp.resolve("private/demo");
        Path pkg = Files.createDirectories(svc.resolve("before/src/main/java/demo"));
        Files.writeString(svc.resolve("before/logast.yaml"), "packs: [logging]\n");
        Files.writeString(pkg.resolve("Ledger.java"), """
                package demo;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                class Ledger {
                    private static final Logger log = LoggerFactory.getLogger(Ledger.class);

                    /** Bucht einen Eintrag → für das Hauptbuch (non-ASCII: recorded byte for byte). */
                    void book(String entryId) {
                        log.info("Booked entry " + entryId);
                    }
                }
                """);
        return svc;
    }

    @Test
    void recordsThenFindsAChange() throws IOException {
        Path svc = service();
        assertThat(PrivateRegression.services(tmp.resolve("private"))).containsExactly(svc);
        assertThat(PrivateRegression.services(tmp.resolve("absent"))).as("no folder: nothing to run").isEmpty();

        assertThat(PrivateRegression.check(svc, tmp.resolve("w1"), false)).as("nothing recorded yet")
                .singleElement().asString().startsWith("no expected/ yet");
        assertThat(PrivateRegression.check(svc, tmp.resolve("w2"), true)).as("recorded").isEmpty();
        assertThat(svc.resolve("expected/src/main/java/demo/Ledger.java")).content().contains("[entryId={}]");
        assertThat(PrivateRegression.check(svc, tmp.resolve("w3"), false)).as("the same logast: the same result").isEmpty();

        // a reviewed result that logast no longer produces (as after a change in logast): named, with its line
        Path ledger = svc.resolve("expected/src/main/java/demo/Ledger.java");
        Files.writeString(ledger, Files.readString(ledger).replace("Booked entry", "Entry booked"));
        assertThat(PrivateRegression.check(svc, tmp.resolve("w4"), false))
                .singleElement().asString().startsWith("differs: src/main/java/demo/Ledger.java (first at line ");
    }
}
