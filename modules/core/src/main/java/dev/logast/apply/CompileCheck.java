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

    /** Why this gate gave no verdict or does not trust Lombok (#81), or null; known after the first check. */
    public String problem() {
        return tooNew != null ? tooNew : lombokProblem;
    }

    /**
     * Set once javac reports class files newer than the JDK logast runs on (#81): such a compile cannot be trusted
     * (on a real service Lombok's loggers went missing in every file), so the gate gives no verdict from then on.
     */
    private String tooNew;
    private static final java.util.regex.Pattern WRONG_VERSION = java.util.regex.Pattern.compile(
            "class file has wrong version (\\d+)\\.\\d+, should be (\\d+)\\.\\d+");

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
        if (javac == null || after.isEmpty() || tooNew != null) {
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

    /**
     * #279: test files (relative paths, unchanged) that compiled against {@code original}'s sources and do not against
     * {@code work}'s: a fix changed a signature they call. The first new error of each.
     */
    public Map<String, String> brokenTests(Path original, Path work, List<String> tests) {
        Map<String, String> out = new LinkedHashMap<>();
        if (javac == null || tests.isEmpty() || tooNew != null) {
            return out;
        }
        Map<String, String> texts = new LinkedHashMap<>();
        for (String f : tests) {
            try {
                texts.put(f, dev.logast.parse.SourceText.read(work.resolve(f)));
            } catch (IOException e) {
                return out;
            }
        }
        Map<String, Set<String>> old = errors(original, texts);
        Map<String, Set<String>> now = errors(work, texts);
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
        // the folder is part of the key: the same texts compile differently against another tree (#279)
        String key = work.toAbsolutePath() + "\u0001" + texts.entrySet().stream().map(e -> e.getKey() + "\u0000" + e.getValue().hashCode()
                + "\u0000" + e.getValue().length()).sorted().reduce("", String::concat);
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
        // #305: javac runs annotation processors only on the files it is given, not on those it reads from the source
        // path: a type there whose source uses Lombok has none of its generated members. When an error points at such a
        // type, compile again with its file given too (its own errors are not ours: it has no entry in fileOf)
        String roots = sourceRoots(work);
        Set<Path> processed = new HashSet<>();
        DiagnosticCollector<JavaFileObject> diags;
        for (int pass = 0; ; pass++) {
            diags = analyze(units, roots);
            if (diags == null) {
                return out;   // javac itself failed: no verdict, the round goes on as before
            }
            List<Path> lombokTypes = lombok == null || pass == 10 ? List.of() : lombokSources(diags, fileOf, roots, processed);
            if (lombokTypes.isEmpty()) {
                break;
            }
            for (Path f : lombokTypes) {
                processed.add(f);
                units.add(fm(f));
            }
        }
        Set<String> flat = new HashSet<>();
        if (System.getenv("LOGAST_DEBUG_COMPILE") != null) {
            // #81: everything javac said about this compile, unfiltered, to find why a gate verdict differs from the build
            System.err.println("[compile check] " + texts.keySet() + " lombok=" + lombok + " lombokWorks=" + lombokWorks());
            for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
                System.err.println("[compile check]   " + d.getKind() + " " + (d.getSource() == null ? "-" : d.getSource().toUri())
                        + ":" + d.getLineNumber() + " " + d.getCode() + " " + d.getMessage(Locale.ROOT).replaceAll("\\s+", " "));
            }
        }
        for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
            java.util.regex.Matcher v = WRONG_VERSION.matcher(d.getMessage(Locale.ROOT));
            if (d.getKind() == Diagnostic.Kind.ERROR && v.find() && tooNew == null) {
                tooNew = "the classpath has classes for a newer Java (Java " + (Integer.parseInt(v.group(1)) - 44)
                        + ") than logast runs on (Java " + Runtime.version().feature() + "): run logast on Java "
                        + (Integer.parseInt(v.group(1)) - 44) + " or newer";
            }
        }
        if (tooNew != null) {
            return new HashMap<>();   // no verdict: the round goes on as if there were no compiler
        }
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

    /** One javac analysis of these units; null when javac itself failed. */
    private DiagnosticCollector<JavaFileObject> analyze(List<JavaFileObject> units, String sp) {
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = javac.getStandardFileManager(diags, Locale.ROOT, StandardCharsets.UTF_8)) {
            // #284: javac stops before flow analysis (unreachable code, a missing return, an unassigned final) once
            // attribution found an error; on a partial classpath there always is one (an unresolved type, ignored as
            // environmental), so go on through FLOW regardless
            List<String> options = new ArrayList<>(List.of("-implicit:none", "-nowarn", "-Xlint:none", "-XDsuppressNotes",
                    "-Xmaxerrs", "100000", "-XDshould-stop.ifError=FLOW"));
            if (lombok != null) {
                options.addAll(List.of("-proc:full", "-processorpath", lombok.toString()));
            } else {
                options.add("-proc:none");
            }
            String cp = String.join(java.io.File.pathSeparator, classpath.stream().map(Path::toString).toList());
            if (!cp.isEmpty()) {
                options.addAll(List.of("-classpath", cp));
            }
            if (!sp.isEmpty()) {
                options.addAll(List.of("-sourcepath", sp));
            }
            JavacTask task = (JavacTask) javac.getTask(new StringWriter(), fm, diags, options, null, units);
            task.analyze();
            return diags;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static final java.util.regex.Pattern LOCATION =
            // "location: class x.Order", and (#305) "constructor Order in class x.Order cannot be applied" /
            // "method of in class x.Order": Lombok's constructors, builders and accessors
            java.util.regex.Pattern.compile("(?:location: (?:variable \\S+ of type|class|interface|enum|record)|\\bin (?:class|interface|enum|record)) ([\\w.$]+)");

    /**
     * #305: the source files, not yet given to javac, of the types an error in our files names as the location of a
     * missing symbol, when they use Lombok.
     */
    private static List<Path> lombokSources(DiagnosticCollector<JavaFileObject> diags, Map<URI, String> fileOf, String roots,
                                            Set<Path> processed) {
        Set<Path> found = new java.util.LinkedHashSet<>();
        for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
            if (d.getKind() != Diagnostic.Kind.ERROR || d.getSource() == null || !fileOf.containsKey(d.getSource().toUri())) {
                continue;
            }
            java.util.regex.Matcher m = LOCATION.matcher(d.getMessage(Locale.ROOT).replaceAll("\\s+", " "));
            while (m.find()) {
                Path f = sourceOf(m.group(1).replaceAll("<.*", ""), roots);
                if (f != null && !processed.contains(f) && !fileOf.containsKey(f.toAbsolutePath().normalize().toUri())) {
                    try {
                        if (dev.logast.parse.SourceText.read(f).contains("lombok")) {
                            found.add(f);
                        }
                    } catch (IOException e) {
                        // unreadable: leave it on the source path
                    }
                }
            }
        }
        return List.copyOf(found);
    }

    /** The source file of a type (or of the type a nested one is declared in) in one of the source roots, or null. */
    private static Path sourceOf(String type, String roots) {
        if (roots.isEmpty()) {
            return null;
        }
        String name = type.replace('$', '.');
        while (!name.isEmpty()) {
            for (String root : roots.split(java.io.File.pathSeparator)) {
                Path f = Path.of(root, name.replace('.', '/') + ".java");
                if (Files.isRegularFile(f)) {
                    return f;
                }
            }
            int dot = name.lastIndexOf('.');
            if (dot < 0) {
                return null;
            }
            name = name.substring(0, dot);
        }
        return null;
    }

    private static JavaFileObject fm(Path f) {
        return new SimpleJavaFileObject(f.toAbsolutePath().normalize().toUri(), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
                return dev.logast.parse.SourceText.read(f);
            }
        };
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
    static boolean environmental(String code, String message) {
        // an unknown class used as a qualifier (Factory.builder()) is reported as a variable
        // #320: a static import from a class javac cannot find (a test library missing from the main classpath) is read
        // as a package: "static import only from classes and interfaces"
        return code.equals("compiler.err.doesnt.exist") || code.equals("compiler.err.static.imp.only.classes.and.interfaces")
                || code.startsWith("compiler.err.cant.resolve")
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
