package dev.logast.parse;

import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.UpdateSourcePositions;
import org.openrewrite.java.tree.J;
import org.openrewrite.tree.ParseError;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/** Parses all main sources of a project once, with full type attribution against the given classpath. */
public final class ProjectParser {
    private static final Set<String> SKIP_DIRS = Set.of("build", "target", ".git", ".gradle", ".idea", "node_modules", ".logast", "out");

    /**
     * Full parse in one compilation, so every cross-file type resolves from source. Class directories on the
     * classpath are left out: the sources are the truth, and classes that do not match them (stale or copied) must not
     * change what is resolved.
     */
    public ParsedProject parse(Path root, List<Path> classpath) {
        Path absRoot = root.toAbsolutePath().normalize();
        List<Path> jars = classpath.stream().filter(p -> !Files.isDirectory(p)).toList();
        ParsedProject p = parseFiles(absRoot, findSources(absRoot), jars, List.of());
        return new ParsedProject(p.root(), p.files(), List.copyOf(classpath), p.parseErrors(), p.probe(), p.discovered());
    }

    /** A class directory with actual .class files (an empty dir left by a failed compile does not count). */
    public static boolean hasCompiledClasses(List<Path> classpath) {
        for (Path p : classpath) {
            if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.walk(p, 12)) {
                    if (s.anyMatch(f -> f.toString().endsWith(".class"))) {
                        return true;
                    }
                } catch (IOException e) {
                    // unreadable: ignore
                }
            }
        }
        return false;
    }

    private ParsedProject parseFiles(Path absRoot, List<Path> sources, List<Path> classpath, List<SourceFileInfo> kept) {
        List<String> errors = new ArrayList<>();
        List<SourceFile> parsed = sources.isEmpty() ? List.of() : parseSlice(absRoot, sources, classpath, errors);
        ExecutionContext ctx = new InMemoryExecutionContext(t -> errors.add(String.valueOf(t.getMessage())));

        List<SourceFileInfo> files = new ArrayList<>(kept);
        for (SourceFile sf : parsed) {
            if (sf instanceof ParseError pe) {
                errors.add("parse error: " + pe.getSourcePath());
                continue;
            }
            if (!(sf instanceof J.CompilationUnit cu)) {
                continue;
            }
            Path abs = absRoot.resolve(cu.getSourcePath());
            String text = read(abs);
            // UpdateSourcePositions' visitor is stateful: one instance per file
            J.CompilationUnit positioned = (J.CompilationUnit) new UpdateSourcePositions().getVisitor().visit(TypeRepair.repair(cu, ctx), ctx);
            boolean editable = positioned != null && text.equals(positioned.printAll());
            files.add(new SourceFileInfo(abs, cu.getSourcePath().toString().replace('\\', '/'), text,
                    positioned == null ? cu : positioned, Hashing.sha256(text), editable));
        }
        files.sort(Comparator.comparing(SourceFileInfo::relPath));
        // account for every discovered file: analysed, or named in the errors (never silently missing)
        Set<String> analysed = new HashSet<>();
        files.forEach(f -> analysed.add(f.relPath()));
        for (Path src : sources) {
            String rel = absRoot.relativize(src.toAbsolutePath().normalize()).toString().replace('\\', '/');
            if (!analysed.contains(rel) && errors.stream().noneMatch(e -> e.endsWith(rel))) {
                errors.add("not analysed: " + rel);
            }
        }
        return new ParsedProject(absRoot, List.copyOf(files), List.copyOf(classpath), List.copyOf(errors),
                new ClasspathProbe(classpath), kept.size() + sources.size());
    }

    private static List<SourceFile> parseSlice(Path absRoot, List<Path> slice, List<Path> classpath, List<String> errors) {
        ExecutionContext ctx = new InMemoryExecutionContext(t -> errors.add(String.valueOf(t.getMessage())));
        return JavaParser.fromJavaVersion()
                .classpath(classpath)
                .logCompilationWarningsAndErrors(false)
                .build()
                .parse(slice, absRoot, ctx)
                .toList();
    }

    /** Syntax-only check used after applying edits. */
    public static boolean parsesCleanly(Path root, Path relPath, String text) {
        List<SourceFile> out = JavaParser.fromJavaVersion().build()
                .parseInputs(List.of(new org.openrewrite.Parser.Input(relPath, () -> new java.io.ByteArrayInputStream(
                        text.getBytes(java.nio.charset.StandardCharsets.UTF_8)))), root, new InMemoryExecutionContext())
                .toList();
        return out.size() == 1 && out.getFirst() instanceof J.CompilationUnit cu && cu.printAll().equals(text);
    }

    /** All {@code src/main/java} trees under the root; falls back to every .java file outside test dirs. */
    static List<Path> findSources(Path root) {
        List<Path> mainRoots = new ArrayList<>();
        walkDirs(root, dir -> {
            if (dir.endsWith(Path.of("src", "main", "java"))) {
                mainRoots.add(dir);
            }
        });
        List<Path> searchRoots = mainRoots.isEmpty() ? List.of(root) : mainRoots;
        List<Path> out = new ArrayList<>();
        for (Path r : searchRoots) {
            try (Stream<Path> s = Files.walk(r)) {
                s.filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> mainRoots.isEmpty() ? !isTestPath(root.relativize(p)) : true)
                        .filter(p -> !hasSkippedSegment(root.relativize(p)))
                        .forEach(out::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    private static boolean isTestPath(Path rel) {
        for (Path seg : rel) {
            if (seg.toString().equals("test")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSkippedSegment(Path rel) {
        for (Path seg : rel) {
            if (SKIP_DIRS.contains(seg.toString())) {
                return true;
            }
        }
        return false;
    }

    private static void walkDirs(Path dir, java.util.function.Consumer<Path> visitor) {
        visitor.accept(dir);
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isDirectory)
                    .filter(p -> !SKIP_DIRS.contains(p.getFileName().toString()))
                    .sorted()
                    .forEach(p -> walkDirs(p, visitor));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<Path> readClasspathFile(Path file) {
        try {
            String content = Files.readString(file).trim();
            if (content.isEmpty()) {
                return List.of();
            }
            String[] parts = content.contains("\n") ? content.split("\\R") : content.split(java.io.File.pathSeparator);
            return Arrays.stream(parts).map(String::trim).filter(s -> !s.isEmpty()).map(Path::of).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
