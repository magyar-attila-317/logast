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
        return repo(repo, name, outDir, cacheDir, classpathFile, noBuilds, flyway, envs, helmRender, root -> null);
    }

    /** @param analysed #184: an analysis already made for a root (the fleet scan's), else null for that root */
    static RepoExport.Result repo(Path repo, String name, Path outDir, Path cacheDir, Path classpathFile, boolean noBuilds,
                                  Export.FlywayOptions flyway, List<String> envs, List<String> helmRender,
                                  java.util.function.Function<Path, Export.Analysed> analysed) throws IOException {
        Path r = repo.toAbsolutePath().normalize();
        RepoExport.Options opts = new RepoExport.Options(root -> classpath(root, cacheDir.resolve(slug(r, root)), classpathFile, noBuilds),
                flyway, envs, helmRender, analysed);
        return RepoExport.export(r, name, McpCmd.build(), opts, outDir);
    }

    static final String STAMP = ".export.json";

    /** What decides whether an export is still current: logast's version and the options that change the facts. */
    static String optionsKey(String version, Export.FlywayOptions flyway, Path classpathFile, boolean noBuilds) {
        return "logast " + version + (flyway == null ? "" : "; flyway rows=" + flyway.rows() + " image=" + flyway.image())
                + (classpathFile == null ? "" : "; classpath=" + classpathFile.toAbsolutePath().normalize()) + (noBuilds ? "; no-builds" : "");
    }

    /** #184: the member's last export was of this commit with these options (a member without git is always exported). */
    static boolean upToDate(Path outDir, String commit, String options) {
        Path f = outDir.resolve(STAMP);
        if (commit == null || !Files.isRegularFile(f)) {
            return false;
        }
        try {
            var n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(f.toFile());
            return commit.equals(n.path("commit").asText()) && options.equals(n.path("options").asText());
        } catch (IOException e) {
            return false;
        }
    }

    static void stamp(Path outDir, String commit, String options) throws IOException {
        stamp(outDir, commit, options, java.util.Map.of());
    }

    /** @param needs #205: what its migrations needed from other repos, and how that resolved ({@link #needs}) */
    static void stamp(Path outDir, String commit, String options, java.util.Map<String, String> needs) throws IOException {
        Files.createDirectories(outDir);
        var n = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        n.put("commit", commit);
        n.put("options", options);
        if (!needs.isEmpty()) {
            var o = n.putObject("needs");
            new java.util.TreeMap<>(needs).forEach(o::put);
        }
        Files.writeString(outDir.resolve(STAMP), n.toString() + "\n");
    }

    /**
     * #205: the tables the members' migrations create ({@code schema.name} to its columns), from their facts under
     * {@code kb}: what a migration of another member that needs one runs against.
     */
    static java.util.Map<String, List<String>> tablesMade(Path kb) {
        java.util.Map<String, List<String>> out = new java.util.TreeMap<>();
        if (!Files.isDirectory(kb)) {
            return out;
        }
        Path mapped = kb.resolve("mapped");
        KnowledgeBase k = KnowledgeBase.load(KnowledgeBase.factFiles(List.of(kb)).stream().filter(f -> !f.startsWith(mapped)).toList());
        for (var n : k.nodes()) {
            if (n.fact().kind().equals("table") && n.fact().attrs().get("columns") instanceof List<?> cols && !cols.isEmpty()) {
                out.putIfAbsent(n.fact().id().substring("table:".length()).toLowerCase(java.util.Locale.ROOT),
                        cols.stream().map(String::valueOf).toList());
            }
        }
        return out;
    }

    /** #227: the member (its folder under {@code kb}) that makes each table ({@code schema.name}). */
    static java.util.Map<String, String> tableOwners(Path kb) {
        java.util.Map<String, String> out = new java.util.TreeMap<>();
        if (!Files.isDirectory(kb)) {
            return out;
        }
        try (Stream<Path> s = Files.list(kb)) {
            for (Path m : s.filter(Files::isDirectory).filter(d -> !d.getFileName().toString().equals("mapped")
                    && !d.getFileName().toString().equals("mappings")).sorted().toList()) {
                KnowledgeBase k = KnowledgeBase.load(KnowledgeBase.factFiles(List.of(m)));
                for (var n : k.nodes()) {
                    if (n.fact().kind().equals("table") && n.fact().attrs().get("columns") instanceof List<?> cols && !cols.isEmpty()) {
                        out.putIfAbsent(n.fact().id().substring("table:".length()).toLowerCase(java.util.Locale.ROOT), m.getFileName().toString());
                    }
                }
            }
        } catch (IOException e) {
            return out;
        }
        return out;
    }

    /**
     * #227: the tables of other members a member's migrations ran against, each with the member that makes it
     * ({@code cache.products (cache-svc)}).
     */
    static List<String> ranAgainst(List<Need> needs, java.util.Map<String, List<String>> tables, java.util.Map<String, String> owners) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (Need n : needs) {
            if (n.leftOut()) {
                continue;
            }
            String t = dev.logast.export.FlywayExtractor.madeElsewhere(tables, n.asked(), n.searchSchema());
            if (t != null) {
                out.add(t + (owners.containsKey(t) ? " (" + owners.get(t) + ")" : ""));
            }
        }
        return List.copyOf(out);
    }

    /** #205: a need's resolution: the columns of the table another member makes, else {@code -} (none does). */
    static String resolve(java.util.Map<String, List<String>> tables, String asked, String searchSchema) {
        String t = dev.logast.export.FlywayExtractor.madeElsewhere(tables, asked, searchSchema);
        return t == null ? "-" : t + "(" + String.join(", ", tables.get(t)) + ")";
    }

    /** A need: the object a migration asked for, its run's schema, and the migration (for the notes). */
    record Need(String asked, String searchSchema, String via, boolean leftOut, List<String> lost) {
        String key() {
            return asked + "|" + searchSchema;
        }
    }

    /** #205: what a member's migrations needed from other repos (tables, not sequences: those stand in without columns). */
    static List<Need> needs(Path outDir) {
        List<Need> out = new ArrayList<>();
        if (!Files.isDirectory(outDir)) {
            return out;
        }
        KnowledgeBase k = KnowledgeBase.load(KnowledgeBase.factFiles(List.of(outDir)));
        for (var e : k.allEdges()) {
            var a = e.fact().attrs();
            if (Boolean.TRUE.equals(a.get("madeElsewhere")) && a.get("asked") != null && !"sequence".equals(a.get("objectKind"))) {
                List<String> lost = a.get("lost") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
                out.add(new Need(String.valueOf(a.get("asked")), String.valueOf(a.get("searchSchema")), String.valueOf(a.get("via")),
                        Boolean.TRUE.equals(a.get("leftOut")), lost));
            }
        }
        return out;
    }

    /** The needs as the stamp keeps them: each resolved against {@code tables}. */
    static java.util.Map<String, String> resolved(List<Need> needs, java.util.Map<String, List<String>> tables) {
        java.util.Map<String, String> out = new java.util.TreeMap<>();
        needs.forEach(n -> out.put(n.key(), resolve(tables, n.asked(), n.searchSchema())));
        return out;
    }

    /** #205: a need of the member's last export resolves differently now (a repo makes it now, or changed it). */
    static boolean needsChanged(Path outDir, java.util.Map<String, List<String>> tables) {
        Path f = outDir.resolve(STAMP);
        if (!Files.isRegularFile(f)) {
            return false;
        }
        try {
            var needs = new com.fasterxml.jackson.databind.ObjectMapper().readTree(f.toFile()).path("needs");
            var it = needs.fields();
            while (it.hasNext()) {
                var e = it.next();
                String[] k = e.getKey().split("\\|", 2);
                if (!resolve(tables, k[0], k.length > 1 ? k[1] : null).equals(e.getValue().asText())) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        }
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
                     FactWriter fw = new FactWriter(w, Header.of("mapping", McpCmd.build(), "mapped", null))) {
                    FactMapping.Stats st = m.apply(base, fw);
                    int n = st.written().values().stream().mapToInt(Integer::intValue).sum();
                    lines.add("mapping " + name + ": " + n + " facts");
                    st.skipped().forEach((rule, k) -> lines.add("mapping " + name + ": rule " + rule + ": " + k + " skipped, e.g. "
                            + st.skippedExample().get(rule) + " (logast facts map --explain <id> shows why)"));
                }
            } catch (IllegalArgumentException e) {
                lines.add("mapping " + name + ": " + e.getMessage());
            }
        }
        return lines;
    }
}
