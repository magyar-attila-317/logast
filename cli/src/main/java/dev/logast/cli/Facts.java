package dev.logast.cli;

import dev.logast.facts.FactReader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/** #152: commands on facts files (docs/facts.md). */
@Command(name = "facts", hidden = true, description = "Facts files: the format logast export and other extractors write (docs/facts.md)",
        subcommands = Facts.Validate.class)
final class Facts {
    @Command(name = "validate", description = "Check facts files against the format; exit 1 when a line breaks a rule")
    static final class Validate implements Callable<Integer> {
        @Parameters(arity = "1..*", paramLabel = "<file>", description = "Facts files (JSON Lines)")
        List<Path> files;

        @Override
        public Integer call() throws IOException {
            int bad = 0;
            for (Path f : files) {
                if (!Files.isRegularFile(f)) {
                    Out.warn(f + ": no such file");
                    bad++;
                    continue;
                }
                FactReader.Result r;
                try (Reader in = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    r = FactReader.read(in);
                }
                if (r.ok()) {
                    System.out.println(f + ": " + r.nodes().size() + " nodes, " + r.edges().size() + " edges, valid");
                } else {
                    bad++;
                    r.problems().forEach(p -> System.out.println(f + ":" + p.line() + ": " + p.message()));
                }
            }
            return bad == 0 ? 0 : 1;
        }
    }
}
