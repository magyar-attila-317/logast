package dev.logast.cli;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** #94: every subcommand accepts --help and -h: its usage on stdout, exit 0, nothing on stderr. */
class SubcommandHelpTest {
    static Stream<String> subcommands() {
        return new CommandLine(new Main()).getSubcommands().keySet().stream();
    }

    @ParameterizedTest(name = "logast {0} --help")
    @MethodSource("subcommands")
    void subcommandPrintsItsUsage(String name) {
        for (String flag : new String[]{"--help", "-h"}) {
            StringWriter out = new StringWriter();
            StringWriter err = new StringWriter();
            CommandLine cmd = new CommandLine(new Main()).setOut(new PrintWriter(out)).setErr(new PrintWriter(err));
            int exit = cmd.execute(name, flag);
            assertThat(exit).as(flag + " exit code").isZero();
            assertThat(err.toString()).as(flag + " stderr").isEmpty();
            String description = cmd.getSubcommands().get(name).getCommandSpec().usageMessage().description()[0];
            assertThat(out.toString()).as(flag + " usage on stdout").startsWith("Usage: logast " + name)
                    .as("its own description, not the root's").contains(description.substring(0, Math.min(20, description.length())));
        }
    }

    @ParameterizedTest(name = "logast {0} --version")
    @MethodSource("subcommands")
    void subcommandPrintsTheVersion(String name) {
        StringWriter out = new StringWriter();
        int exit = new CommandLine(new Main()).setOut(new PrintWriter(out)).execute(name, "--version");
        assertThat(exit).isZero();
        assertThat(out.toString()).startsWith("logast ");
    }
}
