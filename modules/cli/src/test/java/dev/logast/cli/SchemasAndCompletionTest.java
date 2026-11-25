package dev.logast.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** #321: the schemas of every hand-edited file, a check against them, and shell completion for the CLI. */
class SchemasAndCompletionTest {
    private int exit;

    private String run(String... args) {
        PrintStream was = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            exit = new CommandLine(new Main()).execute(args);
        } finally {
            System.setOut(was);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void completionKnowsEveryCommandAndTheStepAndPackValues() {
        String bash = run("completion");
        assertThat(exit).isZero();
        assertThat(bash).contains("complete -F", "logast").contains("apply", "scan", "schemas", "completion", "fleet")
                .contains("--commit-per-step", "--until").contains("messages", "structure").contains("resilience");
        String zsh = run("completion", "zsh");
        assertThat(zsh).startsWith("autoload -U +X compinit && compinit\nautoload -U +X bashcompinit && bashcompinit\n");
        run("completion", "fish");
        assertThat(exit).isEqualTo(2);
    }

    @Test
    void schemasAreWrittenForEveryHandEditedFile(@TempDir Path dir) {
        String out = run("schemas", dir.toString());
        assertThat(exit).isZero();
        for (String s : new String[]{"logast", "messages", "metrics", "retry", "traffic", "vocabulary-worksheet", "repos", "knowledge",
                "mapping", "config"}) {
            assertThat(dir.resolve(s + ".schema.json")).as(s).isRegularFile();
        }
        assertThat(out).contains("mapping.schema.json", "# $schema: <path to>/mapping.schema.json");
    }

    @Test
    void aFileIsCheckedAgainstItsSchema(@TempDir Path dir) throws Exception {
        Path policy = dir.resolve("logast.yaml");
        Files.writeString(policy, "logging:\n  keyStyle: kebab\n  keystyle: camel\n");
        String out = run("schemas", "--check", policy.toString());
        assertThat(exit).isEqualTo(1);
        assertThat(out).contains("logging.keyStyle \"kebab\" is not one of", "logging.keystyle is not a known key");
        Path mapping = dir.resolve("jobs.yml");
        Files.writeString(mapping, """
                mappings:
                  - name: jobs
                    match: {kind: row, idPrefix: "row:orchestration.job_definition:"}
                    nodes:
                      - {id: "job:{service}:{name}", kind: job, attrs: {schedule: "{cron}"}}
                """);
        assertThat(run("schemas", "--check", mapping.toString(), "--schema", "mapping.schema.json")).contains(": ok (mapping.schema.json)");
        assertThat(exit).isZero();
        Path knowledge = dir.resolve("knowledge.yml");
        Files.writeString(knowledge, "glossary:\n  pledge: A promise to buy\ndecisions:\n  - {link: \"a -> b\", decision: maybe}\n");
        assertThat(run("schemas", "--check", knowledge.toString())).contains("decision \"maybe\" is not one of");
        run("schemas", "--check", dir.resolve("jobs.yml").toString().replace("jobs", "other"));
        assertThat(exit).isNotZero();
    }
}
