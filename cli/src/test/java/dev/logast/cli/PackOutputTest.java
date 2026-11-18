package dev.logast.cli;

import dev.logast.rules.Rules;
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

/** #88: a run talks about the packs it runs; a pack other than logging gets no logging advice. */
class PackOutputTest {
    private static final String TRAFFIC_HINT = "fill calls/day in .logast/traffic.yaml";

    @TempDir
    Path tmp;

    /** A scheduled job (an entrypoint that should log and does not): log coverage and a traffic rate apply to it. */
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

                import org.springframework.scheduling.annotation.Scheduled;

                class CleanupJob {
                    private int removed;

                    @Scheduled(cron = "0 0 * * * *")
                    void cleanup() {
                        removed++;
                    }
                }
                """);
        return app;
    }

    /** Some jar of the test JVM: a non-empty classpath (the job's types are all in its sources). */
    private Path classpathFile() throws IOException {
        String jar = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p -> p.endsWith(".jar")).findFirst().orElseThrow();
        Path f = tmp.resolve("classpath.txt");
        Files.writeString(f, jar + "\n");
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

    @Test
    void logCoverageAndTrafficHintOnlyWithThePacksTheyServe() throws IOException {
        Path app = project();
        String cp = classpathFile().toString();
        assertThat(run("scan", app.toString(), "--pack", "logging", "--classpath-file", cp))
                .as("the logging pack measures log coverage and asks for traffic (log levels)")
                .contains("log coverage").contains(TRAFFIC_HINT);
        assertThat(run("scan", app.toString(), "--pack", "metrics", "--classpath-file", cp))
                .as("metrics frequency uses the traffic too").contains(TRAFFIC_HINT).doesNotContain("log coverage");
        for (String pack : new String[]{"tracing", "resilience", "errors", "audit"}) {
            assertThat(run("scan", app.toString(), "--pack", pack, "--classpath-file", cp)).as("--pack " + pack)
                    .doesNotContain("log coverage").doesNotContain(TRAFFIC_HINT);
            assertThat(run("scan", app.toString(), "--pack", pack, "--format", "summary", "--classpath-file", cp))
                    .as("--pack " + pack + " --format summary").doesNotContain("log coverage");
        }
    }

    @Test
    void packOptionListsEveryPack() {
        for (String command : new String[]{"scan", "apply", "init"}) {
            String help = new CommandLine(new Main()).getSubcommands().get(command).getUsageMessage(CommandLine.Help.Ansi.OFF)
                    .replaceAll("\\s+", " ");
            String option = help.substring(help.lastIndexOf("--pack="));   // the option list, not the synopsis
            int next = option.indexOf(" -", 1);
            String described = next < 0 ? option : option.substring(0, next);
            for (String pack : Rules.packs()) {
                assertThat(described).as(command + " --help, the --pack option").contains(pack);
            }
        }
    }
}
