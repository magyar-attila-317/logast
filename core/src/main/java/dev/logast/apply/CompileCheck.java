package dev.logast.apply;

import com.sun.source.util.JavacTask;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Type-checks the Java files an apply round changes, before and after the round, and reports the errors that only the
 * new text has (#44). A re-parse only proves the syntax; this catches a generated name that clashes with a local
 * variable, an import that shadows another type, a call that does not exist.
 *
 * <p>Errors are compared as kinds (file, error code, message without line numbers), not counted: more uses of a symbol
 * that was already unresolved are no new error. Lombok runs when it is on the classpath, with the project's lombok.config
 * (#76): each text is compiled under its own path in {@code work}, where Lombok looks for the config. Types and packages
 * javac cannot find at all are left out (a partial classpath), and so is a Lombok logger without Lombok.
 */
public final class CompileCheck {
    private final List<Path> classpath;
    private final JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    private final Map<String, Set<String>> cache = new HashMap<>();   // text hash -> errors, for the "before" side
    private final Path lombok;   // the Lombok jar javac runs as annotation processor, else null

    public CompileCheck(List<Path> classpath) {
        this.classpath = List.copyOf(classpath);
        // Lombok generates the loggers (@Slf4j) and accessors the fixes use: run it when it is on the classpath
        this.lombok = classpath.stream().filter(p -> p.getFileName() != null && p.getFileName().toString().startsWith("lombok")
                && p.toString().endsWith(".jar")).findFirst().orElse(null);
    }

    private Boolean lombokWorks;   // probed once (#81)
    private String lombokProblem;

    /** Why Lombok on the classpath is not trusted in this gate (#81), or null; known after the first check. */
    public String lombokProblem() {
        return lombokProblem;
    }

    /**
     * Whether Lombok actually runs here (#81): a jar named lombok on the classpath is not proof. A tiny {@code @Slf4j}
     * class is compiled once with the gate's options; if its {@code log} does not resolve, Lombok's loggers are treated
     * as when Lombok is absent, and {@link #lombokProblem()} says what javac reported.
     */
    private boolean lombokWorks() {
        if (lombok == null) {
            return false;
        }
        if (lombokWorks == null) {
            String probe = "@lombok.extern.slf4j.Slf4j class LogastLombokProbe { void m() { log.info(\"probe\"); } }";
            JavaFileObject unit = new SimpleJavaFileObject(URI.create("string:///LogastLombokProbe.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return probe;
                }
            };
            DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
            try (StandardJavaFileManager fm = javac.getStandardFileManager(diags, Locale.ROOT, StandardCharsets.UTF_8)) {
                List<String> options = new ArrayList<>(List.of("-implicit:none", "-nowarn", "-Xlint:none", "-XDsuppressNotes",
                        "-proc:full", "-processorpath", lombok.toString()));
                String cp = String.join(java.io.File.pathSeparator, classpath.stream().map(Path::toString).toList());
                if (!cp.isEmpty()) {
                    options.addAll(List.of("-classpath", cp));
                }
                ((JavacTask) javac.getTask(new StringWriter(), fm, diags, options, null, List.of(unit))).analyze();
                lombokProblem = diags.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                        .map(d -> d.getMessage(Locale.ROOT).replaceAll("\\s+", " ").strip()).findFirst().orElse(null);
            } catch (IOException | RuntimeException e) {
                lombokProblem = "javac failed with Lombok: " + e;
            }
            lombokWorks = lombokProblem == null;
        }
        return lombokWorks;
    }

    /** False when logast runs on a JRE without a compiler: the check is skipped. */
    public boolean available() {
        return javac != null;
    }

    /**
     * Files (relative to {@code work}) whose new text has errors the old text did not have, with the first new error.
     * {@code after} maps each changed Java file to its new text; the old text is read from {@code work} (missing = new file).
     */
    public Map<String, String> newErrors(Path work, Map<String, String> after) {
        Map<String, String> out = new LinkedHashMap<>();
        if (javac == null || after.isEmpty()) {
            return out;
        }
        Map<String, String> before = new LinkedHashMap<>();
        for (String f : after.keySet()) {
            Path p = work.resolve(f);
            try {
                if (Files.exists(p)) {
                    before.put(f, dev.logast.parse.SourceText.read(p));
                }
            } catch (IOException e) {
                return out;
            }
        }
        Map<String, Set<String>> old = errors(work, before);
        Map<String, Set<String>> now = errors(work, after);
        for (var e : now.entrySet()) {
            Set<String> added = new HashSet<>(e.getValue());
            added.removeAll(old.getOrDefault(e.getKey(), Set.of()));
            if (!added.isEmpty()) {
                out.put(e.getKey(), added.stream().sorted().findFirst().orElseThrow());
            }
        }
        return out;
    }

    /** Error kinds per file, for these texts compiled together; the project's other sources come from the source path. */
    private Map<String, Set<String>> errors(Path work, Map<String, String> texts) {
        Map<String, Set<String>> out = new HashMap<>();
        if (texts.isEmpty()) {
            return out;
        }
        String key = texts.entrySet().stream().map(e -> e.getKey() + "\u0000" + e.getValue().hashCode() + "\u0000" + e.getValue().length())
                .sorted().reduce("", String::concat);
        Set<String> cached = cache.get(key);
        if (cached != null) {
            cached.forEach(s -> out.computeIfAbsent(s.substring(0, s.indexOf('|')), k -> new HashSet<>()).add(s.substring(s.indexOf('|') + 1)));
            return out;
        }
        List<JavaFileObject> units = new ArrayList<>();
        Map<URI, String> fileOf = new HashMap<>();
        for (var e : texts.entrySet()) {
            // the file's real location: Lombok reads lombok.config from its folder upward (#76)
            URI uri = work.resolve(e.getKey()).toAbsolutePath().normalize().toUri();
            fileOf.put(uri, e.getKey());
            units.add(new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return e.getValue();
                }
            });
        }
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = javac.getStandardFileManager(diags, Locale.ROOT, StandardCharsets.UTF_8)) {
            List<String> options = new ArrayList<>(List.of("-implicit:none", "-nowarn", "-Xlint:none", "-XDsuppressNotes",
                    "-Xmaxerrs", "100000"));
            if (lombok != null) {
                options.addAll(List.of("-proc:full", "-processorpath", lombok.toString()));
            } else {
                options.add("-proc:none");
            }
            String cp = String.join(java.io.File.pathSeparator, classpath.stream().map(Path::toString).toList());
            if (!cp.isEmpty()) {
                options.addAll(List.of("-classpath", cp));
            }
            String sp = sourceRoots(work);
            if (!sp.isEmpty()) {
                options.addAll(List.of("-sourcepath", sp));
            }
            JavacTask task = (JavacTask) javac.getTask(new StringWriter(), fm, diags, options, null, units);
            task.analyze();
        } catch (IOException | RuntimeException e) {
            return out;   // javac itself failed: no verdict, the round goes on as before
        }
        Set<String> flat = new HashSet<>();
        for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
            if (d.getKind() != Diagnostic.Kind.ERROR || d.getSource() == null) {
                continue;
            }
            String file = fileOf.get(d.getSource().toUri());
            if (file == null) {
                continue;   // an error in a file read from the source path: not ours
            }
            String message = d.getMessage(Locale.ROOT).replaceAll("\\s+", " ").strip();
            if (!lombokWorks() && lombokLogger(texts.get(file), message) || environmental(d.getCode(), message)) {
                continue;
            }
            out.computeIfAbsent(file, k -> new HashSet<>()).add(d.getCode() + ": " + message);
            flat.add(file + "|" + d.getCode() + ": " + message);
        }
        cache.put(key, flat);
        return out;
    }

    private static final java.util.regex.Pattern LOMBOK_LOG = java.util.regex.Pattern.compile("@(lombok\\.extern\\.[\\w.]+\\.)?(Slf4j|XSlf4j|Log|Log4j|Log4j2|CommonsLog|JBossLog|Flogger|CustomLog)\\b");

    /** Without Lombok on the classpath, the logger of a class with a Lombok log annotation is unresolved: not an error. */
    private static boolean lombokLogger(String text, String message) {
        return message.contains("symbol: variable log") && text != null && LOMBOK_LOG.matcher(text).find();
    }

    /**
     * A type or package javac cannot find: on a partial classpath (a jar missing, a library the fix imports but logast's
     * classpath lacks) that says more about the environment than about the edit. Name clashes, wrong methods on known
     * types and wrong arguments are still errors. A type name a rule misspells slips through here; the e2e compile catches it.
     */
    private static boolean environmental(String code, String message) {
        // an unknown class used as a qualifier (Factory.builder()) is reported as a variable
        return code.equals("compiler.err.doesnt.exist") || code.startsWith("compiler.err.cant.resolve")
                && (message.contains("symbol: class ") || message.matches(".*symbol: variable [A-Z]\\w*( .*)?"));
    }

    /** Every src/main/java folder of the project (all modules). */
    private static String sourceRoots(Path work) {
        List<String> roots = new ArrayList<>();
        try (Stream<Path> s = Files.walk(work, 6)) {
            s.filter(Files::isDirectory)
                    .filter(p -> p.endsWith(Path.of("src", "main", "java")))
                    .filter(p -> work.relativize(p).toString().split("[/\\\\]").length <= 6)
                    .filter(p -> !p.toString().contains("/build/") && !p.toString().contains("/.logast/"))
                    .forEach(p -> roots.add(p.toString()));
        } catch (IOException e) {
            return "";
        }
        return String.join(java.io.File.pathSeparator, roots);
    }
}
