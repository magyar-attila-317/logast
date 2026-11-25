package dev.logast.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** #125: seven commands in --help, questions under explain, the old names still working with a pointer to the new one. */
class CommandsCliTest {
    @TempDir
    Path tmp;

    record Run(int exit, String err, String out) {
    }

    private Path project() throws IOException {
        Path app = tmp.resolve("app");
        Path pkg = app.resolve("src/main/java/demo");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Orders.java"), """
                package demo;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                class Orders {
                    private static final Logger log = LoggerFactory.getLogger(Orders.class);

                    @PostMapping("/orders")
                    void place(String orderId) {
                        log.info("Placed order {}", orderId);
                    }
                }
                """);
        Files.writeString(app.resolve("logast.yaml"), "packs: [logging, metrics]\n");
        return app;
    }

    private static Run run(String... args) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int rc;
        try {
            System.setOut(new PrintStream(o, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(e, true, StandardCharsets.UTF_8));
            rc = new CommandLine(new Main()).execute(args);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        return new Run(rc, e.toString(StandardCharsets.UTF_8).replaceAll("\u001b\\[[0-9;]*m", ""),
                o.toString(StandardCharsets.UTF_8).replaceAll("\u001b\\[[0-9;]*m", ""));
    }

    @Test
    void helpListsTheMainCommands() {
        String help = run("--help").out();
        List<String> listed = new ArrayList<>();
        Matcher m = Pattern.compile("(?m)^  ([a-z]+)  ").matcher(help.substring(help.indexOf("Commands:")));
        while (m.find()) {
            listed.add(m.group(1));
        }
        assertThat(listed).containsExactly("apply", "scan", "review", "verify", "explain", "fleet", "init", "update", "schemas", "completion");
        assertThat(new CommandLine(new Main()).getSubcommands().keySet()).as("hidden, but still there")
                .contains("shape", "classpath", "traffic", "vocabulary", "metrics", "queries", "why", "lifecycle", "policy", "rules", "facts", "export", "mcp");
        assertThat(new CommandLine(new Main()).getSubcommands().get("explain").getSubcommands().keySet())
                .containsExactly("mdc", "lifecycle", "metrics", "queries", "policy", "rules");
    }

    /** Each old name prints what the new form prints, plus one stderr line naming the new form; the new form has no such line. */
    @ParameterizedTest(name = "logast {0} = logast {1}")
    @CsvSource({
            "metrics, explain metrics, ''",
            "metrics, explain metrics, --format json",
            "why, explain mdc, orderId",
            "lifecycle, explain lifecycle, ''",
            "policy, explain policy, ''",
            "queries, explain queries, --stdout",
    })
    void anOldNameStillWorksAndSaysItsNewOne(String old, String now, String extra) throws IOException {
        Path app = project();
        List<String> oldArgs = new ArrayList<>(List.of(old, app.toString()));
        List<String> newArgs = new ArrayList<>(List.of(now.split(" ")));
        newArgs.add(app.toString());
        if (!extra.isEmpty()) {
            oldArgs.addAll(List.of(extra.split(" ")));
            if (!extra.equals("--stdout")) {   // explain queries always prints
                newArgs.addAll(List.of(extra.split(" ")));
            }
        }
        Run before = run(oldArgs.toArray(String[]::new));
        Run after = run(newArgs.toArray(String[]::new));
        assertThat(before.exit()).as(before.err()).isZero();
        assertThat(after.exit()).as(after.err()).isZero();
        assertThat(before.err()).containsOnlyOnce("`logast " + old + "` is now `logast " + now + "` (this name goes in a later release)");
        assertThat(after.err()).doesNotContain("is now");
        assertThat(before.out()).as("the same output, nothing added on stdout").isEqualTo(after.out()).isNotEmpty();
    }

    @Test
    void rulesUnderItsOldNameToo() {
        Run before = run("rules");
        Run after = run("explain", "rules");
        assertThat(before.err()).contains("`logast rules` is now `logast explain rules`");
        assertThat(before.out()).isEqualTo(after.out()).contains("LOG_FORMAT");
    }

    @Test
    void machineFormatsStayCleanOnStdout() throws IOException {
        Path app = project();
        Run r = run("metrics", app.toString(), "--format", "json");
        new ObjectMapper().readTree(r.out());   // throws when anything else is on stdout
        assertThat(r.err()).contains("is now `logast explain metrics`");
        Run scan = run("scan", app.toString(), "--format", "json");
        new ObjectMapper().readTree(scan.out());
        assertThat(scan.out()).doesNotContain("reports:");
        assertThat(app.resolve(".logast/reports/metrics.md")).as("written by a json scan too").exists();
    }

    @Test
    void explainMdcTakesTheKeyAloneOrRootAndKey() {
        CommandLine cl = new CommandLine(new Main());
        CommandLine.ParseResult pr = cl.parseArgs("explain", "mdc", "orderId");
        Main.ExplainMdc alone = (Main.ExplainMdc) pr.subcommand().subcommand().commandSpec().userObject();
        alone.resolveArguments();
        assertThat(alone.key).isEqualTo("orderId");
        assertThat(alone.root).isEqualTo(Path.of("."));
        pr = new CommandLine(new Main()).parseArgs("explain", "mdc", "/srv/app", "orderId");
        Main.ExplainMdc both = (Main.ExplainMdc) pr.subcommand().subcommand().commandSpec().userObject();
        both.resolveArguments();
        assertThat(both.key).isEqualTo("orderId");
        assertThat(both.root).isEqualTo(Path.of("/srv/app"));
    }

    @Test
    void explainWithoutATopicListsTheEntrypointsAsBefore() throws IOException {
        Path app = project();
        Run r = run("explain", app.toString());
        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.err()).doesNotContain("is now");
    }

    @Test
    void scanWritesTheReportsAndSaysWhere() throws IOException {
        Path app = project();
        Run r = run("scan", app.toString(), "--format", "summary");
        assertThat(r.out()).endsWith("\nreports: .logast/reports/ metrics.md queries.md lifecycle.txt "
                + "(logast explain metrics|queries|lifecycle prints them)\n");
        assertThat(Files.readString(app.resolve(".logast/reports/metrics.md"))).isEqualTo(run("explain", "metrics", app.toString()).out());
        assertThat(Files.readString(app.resolve(".logast/reports/queries.md"))).isEqualTo(run("explain", "queries", app.toString()).out());
        assertThat(Files.readString(app.resolve(".logast/reports/lifecycle.txt"))).isEqualTo(run("explain", "lifecycle", app.toString()).out());
    }
}
