package dev.logast.cli;

import dev.logast.Logast;
import dev.logast.export.Export;
import dev.logast.export.RepoExport;
import dev.logast.facts.FactWriter;
import dev.logast.facts.Header;
import dev.logast.kb.FactMapping;
import dev.logast.kb.KnowledgeBase;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** #182: a whole repo's facts (any layout), and a fleet's knowledge base folder. */
final class KbExport {
    private KbExport() {
    }

    /**
     * A Java root's classpath: the shared file; else the one the repo's own {@code .logast} has; else (no builds) the one
     * cached from an earlier run; else its Gradle runs once, its output kept in {@code cache} (the repo stays as it was).
     */
    static List<Path> classpath(Path root, Path cache, Path classpathFile, boolean noBuilds) {
        if (classpathFile != null) {
            return Logast.classpath(root, classpathFile);
        }
        List<Path> own = Logast.classpath(root, null);
        if (!own.isEmpty()) {
            return own;
        }
        List<Path> cached = Logast.classpathIn(cache);
        if (!cached.isEmpty() || noBuilds) {
            return cached;
        }
        try (Out.Progress p = new Out.Progress("running Gradle in " + root.getFileName() + " to read the classpath (nothing is compiled)")) {
            Main.Classpath.write(root, cache);
        } catch (IOException e) {
            Out.warn("export: no classpath for " + root + " (" + e.getMessage() + "): its types stay unresolved");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Logast.classpathIn(cache);
    }

    static RepoExport.Result repo(Path repo, String name, Path outDir, Path cacheDir, Path classpathFile, boolean noBuilds,
                                  Export.FlywayOptions flyway, List<String> envs, List<String> helmRender) throws IOException {
        Path r = repo.toAbsolutePath().normalize();
        RepoExport.Options opts = new RepoExport.Options(root -> classpath(root, cacheDir.resolve(slug(r, root)), classpathFile, noBuilds),
                flyway, envs, helmRender);
        return RepoExport.export(r, name, McpCmd.version(), opts, outDir);
    }

    private static String slug(Path repo, Path root) {
        String rel = repo.relativize(root.toAbsolutePath().normalize()).toString().replace('\\', '/');
        return rel.isEmpty() ? "repo" : rel.replace('/', '_');
    }

    /** Applies every {@code <kb>/mappings/*.y(a)ml} to the facts under {@code kb}, into {@code <kb>/mapped/<mapping>.jsonl}. */
    static List<String> mappings(Path kb) throws IOException {
        Path dir = kb.resolve("mappings");
        Path mapped = kb.resolve("mapped");
        List<String> lines = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return lines;
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(f -> f.getFileName().toString().matches(".*\\.ya?ml")).sorted().toList();
        }
        if (files.isEmpty()) {
            return lines;
        }
        Files.createDirectories(mapped);
        try (Stream<Path> s = Files.list(mapped)) {
            for (Path old : s.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList()) {
                Files.delete(old);
            }
        }
        List<Path> facts = KnowledgeBase.factFiles(List.of(kb)).stream().filter(f -> !f.startsWith(mapped)).toList();
        KnowledgeBase base = KnowledgeBase.load(facts);
        for (Path f : files) {
            String name = f.getFileName().toString().replaceFirst("\\.ya?ml$", "");
            try {
                FactMapping m = FactMapping.load(f);
                try (Writer w = Files.newBufferedWriter(mapped.resolve(name + ".jsonl"), StandardCharsets.UTF_8);
                     FactWriter fw = new FactWriter(w, Header.of("mapping", McpCmd.version(), "mapped", null))) {
                    FactMapping.Stats st = m.apply(base, fw);
                    int n = st.written().values().stream().mapToInt(Integer::intValue).sum();
                    lines.add("mapping " + name + ": " + n + " facts" + (st.skipped().isEmpty() ? "" : ", " + st.skipped() + " skipped for a missing value"));
                }
            } catch (IllegalArgumentException e) {
                lines.add("mapping " + name + ": " + e.getMessage());
            }
        }
        return lines;
    }
}
