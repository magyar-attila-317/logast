package dev.logast.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #101: the end of scan --format summary (and of apply) says what happens next, by what the person has to do, and
 * knows whether an apply ran: before any apply, after one with only the person's work left, and with nothing left.
 */
class SummaryOutputTest {
    @TempDir
    Path tmp;

    /** A scheduled job that logs nothing and a log line with a vague message. */
    private Path project() throws IOException {
        Path app = tmp.resolve("app");
        Path ann = app.resolve("src/main/java/org/springframework/scheduling/annotation");
        Files.createDirectories(ann);
        Files.writeString(ann.resolve("Scheduled.java"), """
                package org.springframework.scheduling.annotation;

                public @interface Scheduled {
                    String cron() default "";
                }
                """);
        Path pkg = app.resolve("src/main/java/demo");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("CleanupJob.java"), """
                package demo;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.scheduling.annotation.Scheduled;

                class CleanupJob {
                    private static final Logger log = LoggerFactory.getLogger(CleanupJob.class);
                    private int removed;

                    @Scheduled(cron = "0 0 * * * *")
                    void cleanup() {
                        removed++;
                        log.info("Expired coupons removed " + removed);
                    }
                }
                """);
        return app;
    }

    /** The test JVM's jars: slf4j resolves. */
    private Path classpathFile() throws IOException {
        Path f = tmp.resolve("classpath.txt");
        Files.writeString(f, String.join("\n", Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p -> p.endsWith(".jar")).toList()) + "\n");
        return f;
    }

    private static String run(String... args) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(o, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            new CommandLine(new Main()).execute(args);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        return o.toString(StandardCharsets.UTF_8).replaceAll("\u001b\\[[0-9;]*m", "");
    }

    /** Writes a text for every empty "MSG" of messages.yaml, in the form its hint asks for. */
    private static void fillEveryMessage(Path app) throws IOException {
        Path fills = app.resolve(".logast/decisions/messages.yaml");
        var mapper = dev.logast.apply.DecisionYaml.MAPPER;
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(fills.toFile());
        for (var it = root.fields(); it.hasNext(); ) {
            var e = it.next();
            if (!e.getKey().startsWith("_") && e.getValue() instanceof com.fasterxml.jackson.databind.node.ObjectNode entry) {
                java.util.regex.Matcher form = java.util.regex.Pattern.compile("must read as: (\\{subject} \\w+)")
                        .matcher(entry.path("_MSG").asText(""));
                entry.put("MSG", form.find() ? form.group(1).replace("{subject}", "Expired coupon cleanup") : "Expired coupons removed");
            }
        }
        Files.writeString(fills, dev.logast.apply.DecisionYaml.text(dev.logast.apply.DecisionYaml.header(fills), root));
    }

    private static String summary(Path app, String cp) {
        return run("scan", app.toString(), "--pack", "logging", "--format", "summary", "--classpath-file", cp);
    }

    @Test
    void summaryFollowsTheStateOfTheMigration() throws IOException {
        Path app = project();
        String cp = classpathFile().toString();

        // before any apply: logast's own fixes come first, nothing claims an earlier apply
        String before = summary(app, cp);
        assertThat(before).as(before).doesNotContain("Applied earlier")
                .contains("Next: logast apply .   3 fixes logast makes itself")
                .contains("Log lines: 1 — 1 with a finding (.logast/reports/coverage.txt)")
                .contains("Places that should log: 0 of 1 do (jobs 0/1)")
                .doesNotContain("high=").doesNotContain("log calls ").doesNotContain("Next steps");

        // after the apply only the person's work is left: what to fill, then apply; no "apply" first
        String applied = run("apply", app.toString(), "--pack", "logging", "--auto", "--write", "--classpath-file", cp);
        // #122: what the run did, then what is optional, with one pointer; nothing reads as a required step
        // #126: one summary line, the changes by kind in words, the optional work in sentences; no id or file walls
        assertThat(applied).as(applied).containsSubsequence("✓ 3 changes in 2 files", "review: git diff",
                        "Worth a look (optional) — the service works without these:", "1 log line could say more → logast review",
                        "Log lines: 2 — 1 fixed, 1 with a finding", "Also: logast verify .")
                .doesNotContainPattern("(?m)^applied \\(").doesNotContainPattern("(?m)^files \\(")
                .doesNotContain("Next:").doesNotContain("Next steps").doesNotContain("Applied earlier")
                .doesNotContain("--auto --write").doesNotContain("logast vocabulary .").doesNotContain("Waiting on you")
                .doesNotContain("can't continue");
        assertThat(applied.split("Log lines: ", -1)).as("the coverage line once per run").hasSize(2);
        String after = summary(app, cp);
        assertThat(after).as(after).doesNotContain("Next:").containsSubsequence(
                "Applied earlier: 3 changes in 2 files (git diff to review)\n",
                "Optional (1) — left as is, each keeps its default:\n",
                "  1 log line: a text of your own  → .logast/decisions/messages.yaml   (blank: the current text stays)\n",
                "  to decide: logast review (or edit .logast/decisions/*.yaml), then logast apply . again",
                "Fix first (low):\n",
                "  generated wording   src/…/CleanupJob.java:13   MESSAGE_SUGGESTED\n",
                "Log lines: 2 — 1 fixed, 1 with a finding (.logast/reports/coverage.txt)\n");

        // the wording written and applied: nothing is left, and the summary says so
        fillEveryMessage(app);
        run("apply", app.toString(), "--pack", "logging", "--auto", "--write", "--classpath-file", cp);
        String done = summary(app, cp);
        assertThat(done).as(done).containsSubsequence("Applied earlier: 1 change in 1 file (git diff to review)\n",
                        "Nothing left: no fixes pending and nothing waits on you.\n", "Log lines: 2 — 1 fixed, 1 fine (.logast/reports/coverage.txt)")
                .doesNotContain("Optional (").doesNotContain("Fix first").doesNotContain("to decide");
    }
}
