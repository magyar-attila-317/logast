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
        List<Path> sources = findSources(absRoot);
        ParsedProject p = parseFiles(absRoot, sources, generatedSources(absRoot, sources), jars, List.of());
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

    private ParsedProject parseFiles(Path absRoot, List<Path> sources, List<Path> generated, List<Path> classpath, List<SourceFileInfo> kept) {
        List<String> errors = new ArrayList<>();
        List<Path> compiled = new ArrayList<>(sources);
        compiled.addAll(generated);
        List<SourceFile> parsed = sources.isEmpty() ? List.of() : parseSlice(absRoot, compiled, classpath, errors);
        Set<Path> generatedRel = new HashSet<>();
        generated.forEach(g -> generatedRel.add(absRoot.relativize(g.toAbsolutePath().normalize())));
        ExecutionContext ctx = new InMemoryExecutionContext(t -> errors.add(String.valueOf(t.getMessage())));

        List<SourceFileInfo> files = new ArrayList<>(kept);
        for (SourceFile sf : parsed) {
            if (sf instanceof ParseError pe) {
                errors.add("parse error: " + pe.getSourcePath());
                continue;
            }
            if (!(sf instanceof J.CompilationUnit cu) || generatedRel.contains(cu.getSourcePath())) {
                continue;   // generated sources only give their types: never analysed, reported or edited
            }
            files.add(info(absRoot, cu, ctx));
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

    /** One parsed file as the rules see it: positions, repaired types, its text and whether it prints back exactly. */
    static SourceFileInfo info(Path absRoot, J.CompilationUnit cu, ExecutionContext ctx) {
        Path abs = absRoot.resolve(cu.getSourcePath());
        String text = read(abs);
        // UpdateSourcePositions' visitor is stateful: one instance per file
        J.CompilationUnit positioned = (J.CompilationUnit) new UpdateSourcePositions().getVisitor().visit(TypeRepair.repair(cu, ctx), ctx);
        boolean editable = positioned != null && text.equals(positioned.printAll());
        return new SourceFileInfo(abs, cu.getSourcePath().toString().replace('\\', '/'), text,
                positioned == null ? cu : positioned, Hashing.sha256(text), editable);
    }

    static List<SourceFile> parseSlice(Path absRoot, List<Path> slice, List<Path> classpath, List<String> errors) {
        ExecutionContext ctx = new InMemoryExecutionContext(t -> errors.add(String.valueOf(t.getMessage())));
        return JavaParser.fromJavaVersion()
                .classpath(classpath)
                .logCompilationWarningsAndErrors(false)
                .build()
                // the text logast works on (#54: any charset, CRLF as LF), so the tree prints exactly that text
                .parseInputs(slice.stream().map(p -> new org.openrewrite.Parser.Input(p, () -> new java.io.ByteArrayInputStream(
                        read(p).getBytes(java.nio.charset.StandardCharsets.UTF_8)))).toList(), absRoot, ctx)
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
    public static List<Path> findSources(Path root) {
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

    /** Where Gradle and Maven builds put generated sources (OpenAPI, annotation processors, protobuf, jOOQ). */
    private static final List<Path> GENERATED_ROOTS = List.of(Path.of("build", "generated"), Path.of("target", "generated-sources"));

    /**
     * The generated main sources of every module (#51: OpenAPI-generated API interfaces a hand-written controller
     * implements). They are compiled with the sources so their types and annotations resolve, and are never analysed
     * or edited. A generated file declaring a class the sources declare too (a stale copy) is left out.
     */
    public static List<Path> generatedSources(Path root, List<Path> sources) {
        Set<String> declared = new HashSet<>();
        sources.forEach(s -> declared.add(topLevelName(s)));
        List<Path> out = new ArrayList<>();
        walkDirs(root, dir -> {
            for (Path g : GENERATED_ROOTS) {
                Path gen = dir.resolve(g);
                if (!Files.isDirectory(gen)) {
                    continue;
                }
                try (Stream<Path> s = Files.walk(gen)) {
                    s.filter(p -> p.toString().endsWith(".java"))
                            .filter(p -> !isTestPath(gen.relativize(p)))
                            .filter(p -> declared.add(topLevelName(p)))
                            .forEach(out::add);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });
        out.sort(Comparator.naturalOrder());
        return out;
    }

    private static final java.util.regex.Pattern PACKAGE = java.util.regex.Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", java.util.regex.Pattern.MULTILINE);

    /** {@code com.example.Foo} for {@code .../Foo.java} declaring {@code package com.example;}. */
    private static String topLevelName(Path file) {
        java.util.regex.Matcher m = PACKAGE.matcher(read(file));
        String name = file.getFileName().toString().replaceFirst("\\.java$", "");
        return m.find() ? m.group(1) + "." + name : name;
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
            if (seg.toString().equals("src")) {
                return false;   // inside a source tree a "build" or "out" folder is a package (…common.build)
            }
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

    static String read(Path p) {
        return SourceText.readUnchecked(p);
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
