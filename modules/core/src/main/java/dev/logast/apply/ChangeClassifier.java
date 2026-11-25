package dev.logast.apply;

import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * #316: which of an apply's changes can change what the service does. A method whose code, with its log statements
 * left out, reads the same before and after only changed its logging (text, level, arguments, an added line); any
 * other difference is behavioural: a scope or an observation around code, an evaluation moved, a call added, a
 * statement deleted. Build and configuration files count as behavioural too. On a service with few tests these are
 * the changes to read; the rest is logging.
 *
 * <p>From the syntax alone (no types): a log statement is a call on a logger ({@code log}, {@code logger}, {@code LOG},
 * {@code LOGGER}, the policy's name), a fluent one ({@code log.atInfo()…log()}), or logast's {@code AfterCommit.log(…)}.
 */
public final class ChangeClassifier {
    /** A behavioural change: the file, the line in the result, the method (or null for a file), and what kind. */
    public record Behavioural(String file, int line, String method, String what) {
        public String describe() {
            return file + ":" + line + (method == null ? "" : " " + method) + " — " + what;
        }
    }

    private static final Set<String> LOMBOK_LOGGERS = Set.of("Slf4j", "XSlf4j", "Log", "Log4j", "Log4j2", "CommonsLog",
            "JBossLog", "Flogger", "CustomLog");
    private static final Pattern COMMENTS = Pattern.compile("//[^\n]*|/\\*[\\s\\S]*?\\*/");

    private final Set<String> loggers;

    public ChangeClassifier(String policyLoggerName) {
        this.loggers = policyLoggerName == null || policyLoggerName.isBlank()
                ? Set.of("log", "logger", "LOG", "LOGGER")
                : Set.of("log", "logger", "LOG", "LOGGER", policyLoggerName);
    }

    /** The behavioural changes among {@code changed} (paths relative to both roots), in file order. */
    public List<Behavioural> classify(Path root, Path work, List<String> changed) {
        List<Behavioural> out = new ArrayList<>();
        for (String rel : changed) {
            if (rel.contains("/src/test/") || rel.startsWith("src/test/") || rel.startsWith(".logast/")) {
                continue;
            }
            Path before = root.resolve(rel);
            Path after = work.resolve(rel);
            if (!rel.endsWith(".java")) {
                out.add(new Behavioural(rel, 1, null, Files.exists(before) ? "build or configuration changed" : "build or configuration file added"));
                continue;
            }
            if (!Files.exists(before) || !Files.exists(after)) {
                continue;   // a new class (support classes, a metrics catalog): existing code only uses it through changes listed here
            }
            try {
                if (dev.logast.parse.SourceText.read(after).contains("// logast:metrics-catalog")) {   // CatalogRules.MARKER
                    continue;   // #324 M5: a metrics catalog logast keeps: its members count, nothing else changes
                }
            } catch (IOException e) {
                // compared below
            }
            try {
                out.addAll(compare(rel, dev.logast.parse.SourceText.read(before), dev.logast.parse.SourceText.read(after)));
            } catch (IOException e) {
                out.add(new Behavioural(rel, 1, null, "could not be compared"));
            }
        }
        return out;
    }

    /** The behavioural differences between two versions of one Java file. */
    List<Behavioural> compare(String rel, String before, String after) {
        Map<String, String> was = members(before);
        Map<String, String> now = members(after);
        if (was == null || now == null) {
            return List.of(new Behavioural(rel, 1, null, "does not parse"));
        }
        List<Behavioural> out = new ArrayList<>();
        for (var e : now.entrySet()) {
            String key = e.getKey();
            String old = was.get(key);
            if (old == null) {
                out.add(new Behavioural(rel, line(after, key), name(key), key.startsWith("field ") ? "field added" : "method added"));
            } else if (!old.equals(e.getValue())) {
                out.add(new Behavioural(rel, line(after, key), name(key), key.startsWith("field ")
                        ? "field changed" : "code changed besides logging"));
            }
        }
        for (String key : was.keySet()) {
            if (!now.containsKey(key) && !key.startsWith("field ")) {
                out.add(new Behavioural(rel, 1, name(key), "method removed"));
            }
        }
        return out;
    }

    /** Per member ("method Class.name/arity", "field Class.name", "class Class"), its code without logging, normalised. */
    private Map<String, String> members(String text) {
        ExecutionContext ctx = new InMemoryExecutionContext();
        List<SourceFile> parsed = JavaParser.fromJavaVersion().build().parseInputs(List.of(new org.openrewrite.Parser.Input(
                Path.of("Classified.java"), () -> new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)))), null, ctx).toList();
        if (parsed.size() != 1 || !(parsed.getFirst() instanceof J.CompilationUnit cu)) {
            return null;
        }
        // #326 L5: the file's own logger fields count, whatever their name (an AUDIT or activityLog logger)
        Set<String> fileLoggers = new java.util.HashSet<>(loggers);
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations vd, Integer p) {
                if (vd.getTypeExpression() != null && vd.getTypeExpression().printTrimmed().matches("(org\\.slf4j\\.)?Logger")) {
                    vd.getVariables().forEach(v -> fileLoggers.add(v.getSimpleName()));
                }
                return super.visitVariableDeclarations(vd, p);
            }
        }.visit(cu, 0);
        J.CompilationUnit stripped = (J.CompilationUnit) new JavaIsoVisitor<Integer>() {
            @Override
            public J.Block visitBlock(J.Block block, Integer p) {
                J.Block b = super.visitBlock(block, p);
                // #324 M5: a counting call logast adds next to a log line is observability too, not a change of behaviour
                return b.withStatements(b.getStatements().stream().filter(s -> !logStatement(s, fileLoggers) && !metricStatement(s)).toList());
            }
        }.visitNonNull(cu, 0);
        Map<String, String> out = new LinkedHashMap<>();
        for (J.ClassDeclaration c : stripped.getClasses()) {
            collect(c, out);
        }
        return out;
    }

    private void collect(J.ClassDeclaration c, Map<String, String> out) {
        // a Lombok logger annotation (@Slf4j, …) is the logger logast adds, as a field would be
        J.ClassDeclaration header = c.withBody(c.getBody().withStatements(List.of())).withLeadingAnnotations(
                c.getLeadingAnnotations().stream().filter(a -> !LOMBOK_LOGGERS.contains(a.getSimpleName())).toList());
        out.put("class " + c.getSimpleName(), norm(header.printTrimmed()));
        for (Statement s : c.getBody().getStatements()) {
            if (s instanceof J.MethodDeclaration md) {
                int arity = (int) md.getParameters().stream().filter(p -> !(p instanceof J.Empty)).count();
                out.put("method " + c.getSimpleName() + "." + md.getSimpleName() + "/" + arity, norm(md.printTrimmed()));
            } else if (s instanceof J.VariableDeclarations vd) {
                if (vd.getTypeExpression() != null && vd.getTypeExpression().printTrimmed().matches("(org\\.slf4j\\.)?Logger")) {
                    continue;   // the logger logast adds
                }
                for (var v : vd.getVariables()) {
                    out.put("field " + c.getSimpleName() + "." + v.getSimpleName(), norm(vd.printTrimmed()));
                }
            } else if (s instanceof J.ClassDeclaration inner) {
                collect(inner, out);
            } else {
                out.merge("class " + c.getSimpleName(), norm(s.printTrimmed()), (a, b) -> a + " " + b);
            }
        }
    }

    /**
     * #324 M5: a metric call as a statement: a member of a metric catalog ({@code OrderMetrics.shipped()}), the support
     * library's {@code Outcomes.count/tag/countKind/…}, or a counter's {@code increment()}.
     */
    private static boolean metricStatement(Statement s) {
        if (!(s instanceof J.MethodInvocation mi)) {
            return false;
        }
        Expression root = mi;
        while (root instanceof J.MethodInvocation call && call.getSelect() != null) {
            root = call.getSelect();
        }
        String owner = root instanceof J.Identifier id ? id.getSimpleName() : root instanceof J.FieldAccess fa ? fa.getSimpleName() : "";
        return owner.equals("Outcomes") || owner.endsWith("Metrics") && Character.isUpperCase(owner.charAt(0))
                || mi.getSimpleName().equals("increment") && mi.printTrimmed().contains("Counter");
    }

    /** A call on a logger as a statement, a fluent one, or logast's AfterCommit.log(() -> …). */
    private boolean logStatement(Statement s, Set<String> loggers) {
        if (!(s instanceof J.MethodInvocation mi)) {
            return false;
        }
        Expression root = mi;
        while (root instanceof J.MethodInvocation call && call.getSelect() != null) {
            root = call.getSelect();
        }
        if (root instanceof J.Identifier id) {
            return loggers.contains(id.getSimpleName()) || id.getSimpleName().equals("AfterCommit") && mi.getSimpleName().equals("log");
        }
        return root instanceof J.FieldAccess fa && loggers.contains(fa.getSimpleName());
    }

    private static String norm(String code) {
        return COMMENTS.matcher(code).replaceAll(" ").replaceAll("\\s+", " ").strip();
    }

    private static String name(String key) {
        if (key.startsWith("class ")) {
            return key.substring("class ".length());
        }
        String n = key.substring(key.indexOf(' ') + 1);
        int slash = n.indexOf('/');
        return slash < 0 ? n : n.substring(0, slash);
    }

    /** The line of the member in the text: its name followed by "(" for a method, else its name. */
    private static int line(String text, String key) {
        String simple = name(key);
        simple = simple.substring(simple.lastIndexOf('.') + 1);
        Matcher m = Pattern.compile("\\b" + Pattern.quote(simple) + (key.startsWith("method ") ? "\\s*\\(" : "\\b")).matcher(text);
        if (!m.find()) {
            return 1;
        }
        return (int) text.substring(0, m.start()).chars().filter(ch -> ch == '\n').count() + 1;
    }
}
