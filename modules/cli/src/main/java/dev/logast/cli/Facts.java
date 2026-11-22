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
            + "prefix, conditions), look up others, and write new nodes and edges from templates with filters (docs/facts.md)")
    static final class Map implements Callable<Integer> {
        @picocli.CommandLine.Option(names = "--mapping", description = "The mapping file (YAML)")
        Path mapping;
        @picocli.CommandLine.Option(names = "--out", description = "The facts file to write (default: standard output)")
        Path out;
        @picocli.CommandLine.Option(names = "--explain", paramLabel = "<node id>", description = "Say what each rule does "
                + "with this node: matched or why not, its elements, each lookup's node, each fact written or the value it lacked")
        String explain;
        @picocli.CommandLine.Option(names = "--test", paramLabel = "<mappings>", description = "Golden tests (#213): for each "
                + "mapping file in this folder (or this one file), tests/<name>.input.jsonl mapped must give "
                + "tests/<name>.expected.jsonl; exit 1 when one differs")
        Path test;
        @picocli.CommandLine.Option(names = "--update", description = "With --test: write what the mappings give as the expected files")
        boolean update;
        @Parameters(arity = "0..*", paramLabel = "<facts>", description = "Facts files or folders of them")
        List<Path> facts = new java.util.ArrayList<>();

        @Override
        public Integer call() throws IOException {
            if (test != null) {
                return test();
            }
            if (mapping == null || facts.isEmpty()) {
                Out.warn("facts map: give --mapping <file.yaml> and the facts files (or --test <mappings folder>)");
                return 2;
            }
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
            List<String> trace = new java.util.ArrayList<>();
            try (dev.logast.facts.FactWriter fw = new dev.logast.facts.FactWriter(w,
                    dev.logast.facts.Header.of("mapping", McpCmd.build(), repo, null))) {
                stats = m.apply(kb, fw, explain, trace);
            } finally {
                if (out != null) {
                    w.close();
                }
            }
            if (explain != null) {
                System.err.println("explain " + explain + ":");
                trace.forEach(l -> System.err.println("  " + l));
            }
            stats.written().forEach((rule, n) -> System.err.println("mapping " + rule + ": " + n + " facts"));
            stats.skipped().forEach((rule, n) -> System.err.println("mapping " + rule + ": " + n + " skipped, e.g. "
                    + stats.skippedExample().get(rule)));
            if (stats.written().isEmpty()) {
                System.err.println("mapping: no fact matched any rule");
            }
            return 0;
        }

        /** #213: each mapping against its golden files; the header line (logast's build) is not compared. */
        private int test() throws IOException {
            List<Path> mappings = new java.util.ArrayList<>();
            if (Files.isDirectory(test)) {
                try (var s = Files.list(test)) {
                    s.filter(f -> f.getFileName().toString().matches(".*\\.ya?ml")).sorted().forEach(mappings::add);
                }
            } else {
                mappings.add(test);
            }
            int failed = 0;
            int ran = 0;
            for (Path mf : mappings) {
                String name = mf.getFileName().toString().replaceFirst("\\.ya?ml$", "");
                Path dir = mf.toAbsolutePath().getParent().resolve("tests");
                Path input = dir.resolve(name + ".input.jsonl");
                Path expected = dir.resolve(name + ".expected.jsonl");
                if (!Files.isRegularFile(input)) {
                    System.out.println("  - " + name + ": no tests/" + name + ".input.jsonl");
                    continue;
                }
                ran++;
                List<String> got;
                try {
                    dev.logast.kb.FactMapping m = dev.logast.kb.FactMapping.load(mf);
                    java.io.StringWriter sw = new java.io.StringWriter();
                    try (dev.logast.facts.FactWriter fw = new dev.logast.facts.FactWriter(sw, dev.logast.facts.Header.of("mapping", "test", "mapped", null))) {
                        m.apply(dev.logast.kb.KnowledgeBase.load(List.of(input)), fw);
                    }
                    got = facts(sw.toString());
                } catch (IllegalArgumentException e) {
                    System.out.println("  ✗ " + name + ": " + e.getMessage());
                    failed++;
                    continue;
                }
                if (update) {
                    Files.writeString(expected, String.join("\n", got) + (got.isEmpty() ? "" : "\n"));
                    System.out.println("  ✓ " + name + ": " + got.size() + " facts written to tests/" + name + ".expected.jsonl");
                    continue;
                }
                List<String> want = Files.isRegularFile(expected) ? facts(Files.readString(expected)) : List.of();
                if (got.equals(want)) {
                    System.out.println("  ✓ " + name + ": " + got.size() + " facts as expected");
                    continue;
                }
                failed++;
                System.out.println("  ✗ " + name + (Files.isRegularFile(expected) ? ":" : ": no tests/" + name + ".expected.jsonl (--update writes it)"));
                want.stream().filter(l -> !got.contains(l)).forEach(l -> System.out.println("      - " + l));
                got.stream().filter(l -> !want.contains(l)).forEach(l -> System.out.println("      + " + l));
            }
            if (ran == 0) {
                System.out.println("facts map --test: no mapping with tests/<name>.input.jsonl under " + test);
                return 2;
            }
            return failed == 0 ? 0 : 1;
        }

        private static List<String> facts(String text) {
            return text.lines().filter(l -> !l.isBlank() && !l.startsWith("{\"header\"")).sorted().toList();
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
