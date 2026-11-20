package dev.logast.export;

import dev.logast.Logast;
import dev.logast.facts.FactWriter;
import dev.logast.facts.Header;
import dev.logast.fleet.NamedFleet;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * #182: a whole repo's facts, whatever its layout: every Gradle build (a {@code settings.gradle(.kts)}, or a
 * {@code build.gradle(.kts)} with sources outside any other build), every folder with a helmfile, the Terragrunt units
 * wherever they are, and the repo's workflows and Ansible inventory. One facts file per root; every fact's evidence
 * carries its root, so a source reads {@code repo/apps/orders/src/…:line}.
 */
public final class RepoExport {
    private static final Set<String> SKIP = Set.of(".git", "build", "target", "out", "bin", "node_modules", ".gradle", ".idea",
            ".terragrunt-cache", ".logast", "worktrees", "dist", ".cache", "kb");

    /** One root of a repo and what reads it. */
    public record Root(Path dir, String kind) {
    }

    /**
     * @param classpathFor a Java root → its classpath (a shared file, the one its last build left, or its own Gradle)
     * @param flyway       null: no migrations run; else as {@code export --flyway}
     */
    public record Options(Function<Path, List<Path>> classpathFor, Export.FlywayOptions flyway, List<String> envs,
                          List<String> helmRender, Function<Path, Export.Analysed> analysed) {
        public Options(Function<Path, List<Path>> classpathFor, Export.FlywayOptions flyway, List<String> envs, List<String> helmRender) {
            this(classpathFor, flyway, envs, helmRender, root -> null);
        }
    }

    /** What an export wrote and what went wrong. */
    public record Result(List<Path> files, List<String> problems) {
    }

    private RepoExport() {
    }

    /** The roots of a repo, by what is in each folder (no configuration). */
    public static List<Root> roots(Path repo) {
        Path r = repo.toAbsolutePath().normalize();
        List<Path> dirs = new ArrayList<>();
        walk(r, dirs);
        List<Root> out = new ArrayList<>();
        List<Path> builds = new ArrayList<>();
        for (Path d : dirs) {   // Gradle builds: a settings file, else a build file with sources and no build above
            if (Files.isRegularFile(d.resolve("settings.gradle.kts")) || Files.isRegularFile(d.resolve("settings.gradle"))) {
                builds.add(d);
            }
        }
        for (Path d : dirs) {
            boolean build = Files.isRegularFile(d.resolve("build.gradle.kts")) || Files.isRegularFile(d.resolve("build.gradle"))
                    || Files.isRegularFile(d.resolve("pom.xml")) || Files.isDirectory(d.resolve("src/main/java"));   // sources alone too
            if (build && Files.isDirectory(d.resolve("src/main")) && builds.stream().noneMatch(b -> d.startsWith(b))) {
                builds.add(d);
            }
        }
        builds.stream().sorted().forEach(b -> out.add(new Root(b, "java")));
        for (Path d : dirs) {
            if (HelmExtractor.helmfile(d) != null && out.stream().noneMatch(x -> x.kind().equals("helm") && d.startsWith(x.dir()))) {
                out.add(new Root(d, "helm"));
            }
        }
        if (TerraformExtractor.applies(r)) {
            out.add(new Root(r, "terraform"));
        }
        if (WorkflowExtractor.applies(r) || AnsibleRunners.applies(r)) {
            out.add(new Root(r, "workflows"));
        }
        return out;
    }

    private static void walk(Path dir, List<Path> out) {
        if (!out.isEmpty() && Files.isRegularFile(dir.resolve(".git"))) {
            return;   // a git worktree or submodule inside the repo: another checkout
        }
        out.add(dir);
        try (Stream<Path> s = Files.list(dir)) {
            for (Path c : s.filter(Files::isDirectory).sorted().toList()) {
                String n = c.getFileName().toString();
                if (!SKIP.contains(n) && !Files.isSymbolicLink(c)) {
                    walk(c, out);
                }
            }
        } catch (IOException e) {
            // an unreadable folder: nothing in it
        }
    }

    /** Exports every root of {@code repo} into {@code outDir} (one {@code <root>.jsonl} each; the folder's old files are replaced). */
    public static Result export(Path repo, String repoName, String version, Options opts, Path outDir) throws IOException {
        Path r = repo.toAbsolutePath().normalize();
        Files.createDirectories(outDir);
        try (Stream<Path> s = Files.list(outDir)) {   // what an earlier export wrote here, and only that
            for (Path old : s.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList()) {
                Files.delete(old);
            }
        }
        List<Path> files = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        boolean repoFactsDone = false;
        for (Root root : roots(r)) {
            String rel = r.relativize(root.dir()).toString().replace('\\', '/');
            String prefix = rel.isEmpty() ? null : rel;
            String slug = (rel.isEmpty() ? "repo" : rel.replace('/', '_')) + (root.kind().equals("java") ? "" : "." + root.kind());
            Path file = outDir.resolve(slug + ".jsonl");
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                switch (root.kind()) {
                    case "java" -> {
                        boolean repoFacts = !repoFactsDone && root.dir().equals(r);
                        Export.Analysed done = opts.analysed().apply(root.dir());   // #184: the fleet scan's, when it made one
                        Export.java(root.dir(), done != null ? done
                                        : Export.analyse(root.dir(), opts.classpathFor().apply(root.dir()), Logast.policy(root.dir(), null)),
                                repoName, version, opts.flyway(), w, prefix, repoFacts);
                        repoFactsDone |= repoFacts;
                    }
                    case "helm" -> problems.addAll(prefixed(rel, Export.helm(root.dir(), repoName, version, opts.envs(), opts.helmRender(),
                            null, w, prefix, false)));
                    case "terraform" -> problems.addAll(Export.terraform(r, repoName, version, w, false));
                    default -> {
                        if (!repoFactsDone) {
                            try (FactWriter fw = new FactWriter(w, Header.of(WorkflowExtractor.ID, version, repoName, NamedFleet.commit(r)))) {
                                problems.addAll(Export.workflows(r, repoName, fw));
                            }
                            repoFactsDone = true;
                        }
                    }
                }
                files.add(file);
            } catch (IOException | RuntimeException e) {
                problems.add((rel.isEmpty() ? "." : rel) + " (" + root.kind() + "): " + e.getMessage());
            }
        }
        // a root that wrote only its header (workflows already written with the repo's Java root): no file
        for (Path f : List.copyOf(files)) {
            if (Files.readAllLines(f).size() <= 1) {
                Files.delete(f);
                files.remove(f);
            }
        }
        return new Result(files, problems);
    }

    private static List<String> prefixed(String rel, List<String> problems) {
        return problems.stream().map(p -> (rel.isEmpty() ? "" : rel + ": ") + p).toList();
    }
}
