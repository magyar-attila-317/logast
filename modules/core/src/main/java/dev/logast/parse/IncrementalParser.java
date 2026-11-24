package dev.logast.parse;

import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * #308: the rounds of an apply change a few files at a time, yet every rescan parsed the whole project again (about
 * 40 s on a service of 4,000 files, 20 times per pack). After the first full parse this parser re-parses only the files
 * whose text changed, and keeps the other files' trees.
 *
 * <p>The changed files are parsed against classes javac compiled from the same work tree (Lombok and the project's
 * classpath included), into a folder of its own: the classes always match the sources. (An earlier attempt parsed
 * against the project's own compiled classes; whenever those did not match the sources, calls into other files were
 * lost and the results changed.) A new file is parsed and compiled like a changed one (no unchanged file can use its
 * types yet). A changed file whose API changed (members, supertypes, annotations), a removed file, many changed files,
 * or a tree javac cannot compile: the caller parses in full. Private members are not
 * part of the API (a logger field logast adds keeps the other trees valid). The e2e matrix runs
 * every scenario both ways (incremental on the build JDK, full on Java 21) against the same snapshots.
 */
public final class IncrementalParser {
    private final List<Path> jars;
    private final Path lombok;
    private Path classDir;
    private boolean broken;
    /** Files javac cannot compile here (a partial classpath) and those that need them: parsed from source each time. */
    private final java.util.Set<Path> fromSource = new java.util.HashSet<>();
    /** What the last call did ("re-parsed 3 of 2302 files", "full: …"), for the apply trace. */
    public String lastNote = "";

    public IncrementalParser(List<Path> classpath) {
        this.jars = classpath.stream().filter(p -> !Files.isDirectory(p)).toList();
        this.lombok = classpath.stream().filter(p -> p.getFileName() != null && p.getFileName().toString().startsWith("lombok")
                && p.getFileName().toString().endsWith(".jar")).findFirst().orElse(null);
    }

    /** Whether rounds re-parse only what changed: on unless {@code logast.parse} / {@code LOGAST_PARSE} is "full". */
    public static boolean enabled() {
        String mode = System.getProperty("logast.parse", System.getenv("LOGAST_PARSE"));
        return mode == null || !mode.strip().equalsIgnoreCase("full");
    }

    /** The project after its files changed, from {@code previous}; null when it has to be parsed in full. */
    public ParsedProject reparse(ParsedProject previous) {
        if (broken) {
            lastNote = "full parse (javac could not compile the tree)";
            return null;
        }
        Path root = previous.root();
        List<Path> sources = ProjectParser.findSources(root);
        Map<String, SourceFileInfo> before = previous.byRelPath();
        List<Path> changed = new ArrayList<>();
        int known = 0;
        for (Path s : sources) {
            String rel = root.relativize(s.toAbsolutePath().normalize()).toString().replace('\\', '/');
            SourceFileInfo old = before.get(rel);
            if (old == null) {
                // a new file (support classes, a metrics catalog): no unchanged file can use a type that did not exist
                changed.add(s);
                continue;
            }
            known++;
            if (!old.text().equals(ProjectParser.read(s))) {
                changed.add(s);
            }
        }
        if (known != before.size()) {
            lastNote = "full parse (a file was removed)";
            return null;
        }
        if (changed.isEmpty()) {
            lastNote = "re-parsed 0 of " + sources.size() + " files";
            return previous;
        }
        if (changed.size() > Math.max(20, sources.size() / 10)) {
            lastNote = "full parse (" + changed.size() + " of " + sources.size() + " files changed)";
            return null;
        }
        if (classDir == null && !compileAll(root, sources)) {
            broken = true;
            lastNote = "full parse (javac could not compile the tree)";
            return null;
        }
        List<Path> toCompile = changed.stream().filter(f -> !fromSource.contains(f)).toList();
        if (!toCompile.isEmpty() && !compile(toCompile, List.of(classDir)).isEmpty()) {
            classDir = null;   // compiled from a tree with an error: rebuilt from the next tree
            fromSource.clear();
            lastNote = "full parse (a changed file does not compile)";
            return null;
        }
        List<String> errors = new ArrayList<>();
        List<Path> cp = new ArrayList<>(jars);
        cp.add(classDir);
        // the files javac could not compile give their types from source, as in a full parse
        List<Path> inputs = new ArrayList<>(changed);
        fromSource.stream().filter(f -> !changed.contains(f)).sorted().forEach(inputs::add);
        java.util.Set<String> changedRel = new java.util.HashSet<>();
        changed.forEach(f -> changedRel.add(root.relativize(f.toAbsolutePath().normalize()).toString().replace('\\', '/')));
        List<SourceFile> parsed = ProjectParser.parseSlice(root, inputs, cp, errors).stream()
                .filter(sf -> changedRel.contains(sf.getSourcePath().toString().replace('\\', '/'))).toList();
        ExecutionContext ctx = new InMemoryExecutionContext(t -> errors.add(String.valueOf(t.getMessage())));
        Map<String, SourceFileInfo> files = new HashMap<>(before);
        int n = 0;
        for (SourceFile sf : parsed) {
            if (!(sf instanceof J.CompilationUnit cu)) {
                lastNote = "full parse (a changed file does not parse)";
                return null;
            }
            SourceFileInfo now = ProjectParser.info(root, cu, ctx);
            SourceFileInfo old = files.get(now.relPath());
            if (old != null && !api(old.cu()).equals(api(now.cu()))) {
                lastNote = "full parse (the API of " + now.relPath() + " changed)";
                return null;
            }
            files.put(now.relPath(), now);
            n++;
        }
        if (n != changed.size()) {
            lastNote = "full parse (a changed file did not come back from the parser)";
            return null;
        }
        List<SourceFileInfo> list = new ArrayList<>(files.values());
        list.sort(Comparator.comparing(SourceFileInfo::relPath));
        lastNote = "re-parsed " + changed.size() + " of " + sources.size() + " files";
        return new ParsedProject(root, List.copyOf(list), previous.classpath(), previous.parseErrors(), previous.probe(),
                previous.discovered() - before.size() + list.size());
    }

    /** Forget the classes (the project was parsed in full from another tree). */
    public void reset() {
        classDir = null;
        fromSource.clear();
    }

    private boolean compileAll(Path root, List<Path> sources) {
        try {
            classDir = Files.createTempDirectory("logast-classes-");
        } catch (IOException e) {
            return false;
        }
        Path dir = classDir;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteTree(dir), "logast-classes-cleanup"));
        List<Path> all = new ArrayList<>(sources);
        all.addAll(ProjectParser.generatedSources(root, sources));
        fromSource.clear();
        // a partial classpath: leave out the files javac reports, then those that needed them, until the rest compiles
        for (int attempt = 0; attempt < 6; attempt++) {
            List<Path> rest = all.stream().filter(f -> !fromSource.contains(f)).toList();
            java.util.Set<Path> failed = compile(rest, List.of());
            if (failed.isEmpty()) {
                return true;
            }
            fromSource.addAll(failed);
            if (fromSource.size() > all.size() / 3) {
                return false;
            }
        }
        return false;
    }

    /**
     * javac into the class folder, against the jars (and the folder itself for later rounds): the files with errors
     * (all of them when javac itself fails), empty when it compiled.
     */
    private java.util.Set<Path> compile(List<Path> files, List<Path> extra) {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            return new java.util.HashSet<>(files);
        }
        Map<java.net.URI, Path> byUri = new HashMap<>();
        List<JavaFileObject> units = new ArrayList<>();
        for (Path f : files) {
            byUri.put(f.toAbsolutePath().normalize().toUri(), f);
            units.add(new SimpleJavaFileObject(f.toAbsolutePath().normalize().toUri(), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return ProjectParser.read(f);   // the file's own charset (#54)
                }
            });
        }
        List<String> options = new ArrayList<>(List.of("-d", classDir.toString(), "-nowarn", "-Xlint:none", "-XDsuppressNotes",
                "-parameters", "-implicit:none"));
        if (lombok != null) {
            options.addAll(List.of("-proc:full", "-processorpath", lombok.toString()));
        } else {
            options.add("-proc:none");
        }
        List<Path> cp = new ArrayList<>(jars);
        cp.addAll(extra);
        if (!cp.isEmpty()) {
            options.addAll(List.of("-classpath", String.join(java.io.File.pathSeparator, cp.stream().map(Path::toString).toList())));
        }
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = javac.getStandardFileManager(diags, Locale.ROOT, StandardCharsets.UTF_8)) {
            Boolean ok = javac.getTask(new StringWriter(), fm, diags, options, null, units).call();
            java.util.Set<Path> failed = new java.util.HashSet<>();
            if (!Boolean.TRUE.equals(ok)) {
                for (var d : diags.getDiagnostics()) {
                    if (d.getKind() == javax.tools.Diagnostic.Kind.ERROR && d.getSource() != null && byUri.containsKey(d.getSource().toUri())) {
                        failed.add(byUri.get(d.getSource().toUri()));
                    }
                }
                if (failed.isEmpty()) {
                    failed.addAll(files);   // an error without a file: nothing to leave out
                }
            }
            return failed;
        } catch (IOException | RuntimeException e) {
            return new java.util.HashSet<>(files);
        }
    }

    /**
     * What other files see of a file: its classes, their supertypes and annotations, and their members' signatures and
     * annotations. A body edit (a log line, a scope) keeps it; then the other files' trees stay valid.
     */
    static String api(J.CompilationUnit cu) {
        StringBuilder sb = new StringBuilder();
        for (J.ClassDeclaration c : cu.getClasses()) {
            api(c, sb);
        }
        return sb.toString();
    }

    private static void api(J.ClassDeclaration c, StringBuilder sb) {
        JavaType.FullyQualified t = c.getType();
        sb.append("class ").append(t == null ? c.getSimpleName() : t.getFullyQualifiedName()).append(' ').append(c.getKind())
                .append(c.getModifiers().stream().map(m -> m.getType().name()).toList());
        c.getLeadingAnnotations().forEach(a -> sb.append(" @").append(a.printTrimmed()));
        if (t != null) {
            sb.append(" extends ").append(t.getSupertype() == null ? "-" : t.getSupertype().toString())
                    .append(" implements ").append(t.getInterfaces().stream().map(Object::toString).toList());
        }
        sb.append('\n');
        for (Statement s : c.getBody().getStatements()) {
            // a private member (the logger logast adds, a helper) is not visible to other files
            if (s instanceof J.MethodDeclaration md && md.hasModifier(J.Modifier.Type.Private)
                    || s instanceof J.VariableDeclarations v && v.hasModifier(J.Modifier.Type.Private)) {
                continue;
            }
            if (s instanceof J.MethodDeclaration md) {
                sb.append("  method ").append(md.getMethodType() == null ? md.getSimpleName() : md.getMethodType().toString())
                        .append(md.getModifiers().stream().map(m -> m.getType().name()).toList());
                md.getLeadingAnnotations().forEach(a -> sb.append(" @").append(a.printTrimmed()));
                sb.append('\n');
            } else if (s instanceof J.VariableDeclarations vd) {
                sb.append("  field ").append(vd.getType() == null ? "?" : vd.getType().toString())
                        .append(vd.getVariables().stream().map(J.VariableDeclarations.NamedVariable::getSimpleName).toList())
                        .append(vd.getModifiers().stream().map(m -> m.getType().name()).toList());
                vd.getLeadingAnnotations().forEach(a -> sb.append(" @").append(a.printTrimmed()));
                sb.append('\n');
            } else if (s instanceof J.ClassDeclaration inner) {
                api(inner, sb);
            }
        }
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // already gone
        }
    }
}
