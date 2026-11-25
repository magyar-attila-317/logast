package dev.logast.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #9: logast on public Spring Boot services (corpus/corpus.yaml), checking the properties every migration must have
 * without expected files: the classpath resolves, apply settles in one pass, a second apply changes nothing, no CHECK
 * FAILED, and the result compiles with the service's own build. Every pack runs, in vendor mode (the support
 * classes are copied in, so the build needs no published logging-support). Each service is cloned at its pinned commit into a
 * folder JUnit removes afterwards. Runs only in {@code ./gradlew :cli:corpus} (network, minutes per service).
 */
class CorpusRun {
    @TempDir
    Path work;

    @TestFactory
    Stream<DynamicTest> publicServicesMigrateCleanly() throws IOException {
        Path file = Path.of(System.getProperty("logast.corpus.file"));
        String only = System.getProperty("logast.corpus.only", "");
        JsonNode services = new ObjectMapper(new YAMLFactory()).readTree(file.toFile()).path("services");
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode s : services) {
            String name = s.path("name").asText();
            if (!only.isBlank() && !List.of(only.split(",")).contains(name)) {
                continue;
            }
            tests.add(DynamicTest.dynamicTest(name, () -> check(name, s.path("repo").asText(), s.path("commit").asText(),
                    s.path("dir").asText("."))));
            tests.add(DynamicTest.dynamicTest(name + " (logging pack: behaviour preserved)", () -> loggingOnly(name,
                    s.path("repo").asText(), s.path("commit").asText(), s.path("dir").asText("."))));
        }
        return tests.stream();
    }

    private void check(String name, String repo, String commit, String dir) throws Exception {
        Path clone = work.resolve(name);
        exec(work, "git", "clone", "--quiet", "--filter=blob:none", repo, clone.toString());
        exec(clone, "git", "checkout", "--quiet", commit);
        Path app = clone.resolve(dir).normalize();
        // every pack, and the support classes copied in (vendor): no logging-support release has to be reachable
        if (!Files.exists(app.resolve("logast.yaml")) && !Files.exists(app.resolve("logast.yml"))) {
            Files.writeString(app.resolve("logast.yaml"), "packs: [logging, tracing, metrics, resilience, errors, audit]\n"
                    + "support:\n  mode: vendor\n");
        }

        String classpath = cli("classpath", app.toString());
        assertThat(Files.list(app.resolve(".logast")).anyMatch(p -> p.getFileName().toString().startsWith("classpath")))
                .as(name + ": the classpath resolved\n" + classpath).isTrue();

        String first = cli("apply", app.toString(), "--auto", "--write");
        decisions(name, clone);
        String trace = Files.readString(app.resolve(".logast/reports/apply-trace.txt"));
        String afterPass1 = trace.contains("== pass 2") ? trace.substring(trace.indexOf("== pass 2")) : "";
        assertThat(afterPass1).as(name + ": pass 2 of the first apply applies nothing\n" + trace).doesNotContain("applying");
        // the first apply's result as a commit of the throwaway clone: what a second apply changes is then a plain diff
        exec(clone, "git", "-c", "user.name=corpus", "-c", "user.email=corpus@localhost", "add", "-A");
        exec(clone, "git", "-c", "user.name=corpus", "-c", "user.email=corpus@localhost", "commit", "--quiet", "-m", "first apply");
        String second = cli("apply", app.toString(), "--auto", "--write");
        String diff = exec(clone, "git", "diff");
        assertThat(second).as(name + ": a second apply changes nothing\n" + second + "\nwhat it changed:\n" + diff).contains("nothing to change");
        assertThat(first + second).as(name + ": no CHECK FAILED").doesNotContain("CHECK FAILED");

        Path gradlew = app.resolve("gradlew");
        String compiled = exec(app, gradlew.toString(), "compileJava", "--quiet", "--console=plain");
        assertThat(compiled).as(name + ": the result compiles with the service's own build").doesNotContain("FAILED");
    }

    /**
     * #7: with the logging pack alone, every Java file keeps its behaviour: the same non-observability statements as
     * before ({@link BehaviourCheck#behaviour}), on code nobody wrote for logast's tests.
     */
    private void loggingOnly(String name, String repo, String commit, String dir) throws Exception {
        Path clone = work.resolve(name + "-logging");
        exec(work, "git", "clone", "--quiet", "--filter=blob:none", repo, clone.toString());
        exec(clone, "git", "checkout", "--quiet", commit);
        Path app = clone.resolve(dir).normalize();
        Files.writeString(app.resolve("logast.yaml"), "packs: [logging]\nsupport:\n  mode: vendor\n");
        java.util.Map<String, String> before = new java.util.TreeMap<>();
        try (Stream<Path> files = Files.walk(app.resolve("src"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                before.put(app.relativize(p).toString(), Files.readString(p));
            }
        }
        cli("classpath", app.toString());
        cli("apply", app.toString(), "--auto", "--write");
        List<String> changed = new ArrayList<>();
        for (var e : before.entrySet()) {
            String now = Files.readString(app.resolve(e.getKey()));
            if (!BehaviourCheck.behaviour(e.getValue()).equals(BehaviourCheck.behaviour(now))) {
                List<String> gone = new ArrayList<>(BehaviourCheck.behaviour(e.getValue()));
                gone.removeAll(BehaviourCheck.behaviour(now));
                List<String> added = new ArrayList<>(BehaviourCheck.behaviour(now));
                added.removeAll(BehaviourCheck.behaviour(e.getValue()));
                changed.add(e.getKey() + "\n  gone:  " + gone + "\n  added: " + added);
            }
        }
        assertThat(changed).as(name + ": non-observability statements the logging pack changed").isEmpty();
    }

    /**
     * #328: the first apply's judgement calls (catches, exceptions attached, metric names and families) against the
     * committed baseline testing/corpus/decisions/<service>.txt; written there with -Pcorpus.updateDecisions (and when
     * there is none yet), else a difference fails the run with the diff.
     */
    private static void decisions(String name, Path clone) throws Exception {
        exec(clone, "git", "add", "-A", "--intent-to-add");   // new files (catalogs) show in the diff too
        String now = DecisionSummary.of(name, exec(clone, "git", "diff", "--", ".", ":(exclude)**/.logast/**"));
        Path baseline = Path.of(System.getProperty("logast.corpus.decisions")).resolve(name + ".txt");
        boolean update = Boolean.getBoolean("logast.corpus.updateDecisions");
        if (update || !Files.exists(baseline)) {
            Files.createDirectories(baseline.getParent());
            Files.writeString(baseline, now);
            System.out.println(name + ": decisions written to " + baseline + (update ? "" : " (no baseline yet: review and commit it)"));
            return;
        }
        assertThat(now).as(name + ": the apply's decisions differ from " + baseline + " (-Pcorpus.updateDecisions after a deliberate "
                + "change, with the commit that explains it)").isEqualTo(Files.readString(baseline));
    }

    /** logast in-process; its output. */
    private static String cli(String... args) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int rc;
        try {
            System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(buf, true, StandardCharsets.UTF_8));
            rc = new CommandLine(new Main()).execute(args);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        String text = buf.toString(StandardCharsets.UTF_8);
        assertThat(rc).as("logast " + String.join(" ", args) + "\n" + text).isZero();
        return text;
    }

    /** A process in {@code dir}; fails on a non-zero exit. */
    private static String exec(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(30, TimeUnit.MINUTES)).as(String.join(" ", cmd) + ": timed out").isTrue();
        assertThat(p.exitValue()).as(String.join(" ", cmd) + " in " + dir + "\n" + out).isZero();
        return out;
    }
}
