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
        subcommands = {Facts.Validate.class, Facts.Map.class})
final class Facts {
    @Command(name = "map", description = "Company-specific facts from a mapping file: rules that match facts (kind, id "
            + "prefix, attributes), look up others by id and write new nodes and edges from templates (docs/facts.md)")
    static final class Map implements Callable<Integer> {
        @picocli.CommandLine.Option(names = "--mapping", required = true, description = "The mapping file (YAML)")
        Path mapping;
        @picocli.CommandLine.Option(names = "--out", description = "The facts file to write (default: standard output)")
        Path out;
        @Parameters(arity = "1..*", paramLabel = "<facts>", description = "Facts files or folders of them")
        List<Path> facts;

        @Override
        public Integer call() throws IOException {
            dev.logast.kb.FactMapping m;
            try {
                m = dev.logast.kb.FactMapping.load(mapping);
            } catch (IllegalArgumentException e) {
                Out.warn(mapping + ": " + e.getMessage());
                return 2;
            }
            dev.logast.kb.KnowledgeBase kb = dev.logast.kb.KnowledgeBase.load(dev.logast.kb.KnowledgeBase.factFiles(facts));
            kb.problems().forEach(p -> System.err.println("left out: " + p));
            String repo = kb.repos().size() == 1 ? kb.repos().iterator().next() : "mapped";
            java.io.Writer w = out == null ? new java.io.OutputStreamWriter(System.out, StandardCharsets.UTF_8)
                    : Files.newBufferedWriter(out, StandardCharsets.UTF_8);
            dev.logast.kb.FactMapping.Stats stats;
            try (dev.logast.facts.FactWriter fw = new dev.logast.facts.FactWriter(w,
                    dev.logast.facts.Header.of("mapping", McpCmd.build(), repo, null))) {
                stats = m.apply(kb, fw);
            } finally {
                if (out != null) {
                    w.close();
                }
            }
            stats.written().forEach((rule, n) -> System.err.println("mapping " + rule + ": " + n + " facts"));
            stats.skipped().forEach((rule, n) -> System.err.println("mapping " + rule + ": " + n + " skipped for a missing value, e.g. "
                    + stats.skippedExample().get(rule)));
            if (stats.written().isEmpty()) {
                System.err.println("mapping: no fact matched any rule");
            }
            return 0;
        }
    }

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
