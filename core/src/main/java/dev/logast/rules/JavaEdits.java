package dev.logast.rules;

import dev.logast.model.Annotations;
import dev.logast.model.ClassInfo;
import dev.logast.orders.Edit;
import dev.logast.parse.Pos;
import dev.logast.parse.SourceFileInfo;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Source edits that several rules need: imports, logger fields, statement removal. */
public final class JavaEdits {
    private static final Pattern IMPORT = Pattern.compile("(?m)^import\\s+(static\\s+)?[\\w.]+(\\.\\*)?\\s*;[^\\n]*$");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+[\\w.]+\\s*;[^\\n]*$");

    private JavaEdits() {
    }

    /** Insert {@code import fqn;} unless already imported (explicitly, by wildcard, or same package). */
    public static Edit ensureImport(SourceFileInfo f, String fqn) {
        String text = f.text();
        String pkg = fqn.substring(0, fqn.lastIndexOf('.'));
        if (text.contains("import " + fqn + ";") || text.contains("import " + pkg + ".*;")
                || text.matches("(?s).*package\\s+" + Pattern.quote(pkg) + "\\s*;.*") && !pkg.isEmpty()) {
            return null;
        }
        Matcher m = IMPORT.matcher(text);
        int at = -1;
        while (m.find()) {
            String imported = m.group().replaceFirst("^import\\s+", "").replaceFirst("\\s*;.*$", "");
            if (m.group(1) == null && imported.compareTo(fqn) > 0) {
                // keep imports sorted: insert before the first import that sorts after this one
                return Edit.insert(f.relPath(), m.start(), "import " + fqn + ";\n");
            }
            at = m.end();
        }
        if (at >= 0) {
            return Edit.insert(f.relPath(), Text.nextLine(text, at), "import " + fqn + ";\n");
        }
        Matcher p = PACKAGE.matcher(text);
        if (p.find()) {
            return Edit.insert(f.relPath(), Text.nextLine(text, p.end()), "\nimport " + fqn + ";\n");
        }
        return Edit.insert(f.relPath(), 0, "import " + fqn + ";\n\n");
    }

    public static List<Edit> imports(SourceFileInfo f, String... fqns) {
        // imports landing at the same offset are merged into one edit, in sorted order
        java.util.TreeMap<Integer, java.util.TreeSet<String>> byOffset = new java.util.TreeMap<>();
        for (String fqn : fqns) {
            Edit e = ensureImport(f, fqn);
            if (e != null) {
                byOffset.computeIfAbsent(e.start(), k -> new java.util.TreeSet<>()).add(e.text());
            }
        }
        List<Edit> out = new ArrayList<>();
        byOffset.forEach((at, texts) -> {
            String joined = String.join("", texts);
            out.add(Edit.insert(f.relPath(), at, joined.startsWith("\n") ? "\n" + joined.replace("\n\n", "\n").substring(1) : joined));
        });
        return out;
    }

    /** @param edits empty when the logger already exists; null name when one cannot be added (interface/enum) */
    public record LoggerPlan(String name, List<Edit> edits) {
    }

    public static LoggerPlan logger(ClassInfo owner, String preferredName) {
        return logger(owner, preferredName, false);
    }

    /** @param lombok Lombok is on the classpath: a class without a logger gets {@code @Slf4j} (its logger is "log") */
    public static LoggerPlan logger(ClassInfo owner, String preferredName, boolean lombok) {
        for (Statement s : owner.decl().getBody().getStatements()) {
            if (s instanceof J.VariableDeclarations vd && TypeUtils.isAssignableTo("org.slf4j.Logger", vd.getType())) {
                return new LoggerPlan(vd.getVariables().getFirst().getSimpleName(), List.of());
            }
        }
        if (owner.decl().getLeadingAnnotations().stream().anyMatch(a -> Annotations.is(a, "lombok.extern.slf4j.Slf4j"))) {
            return new LoggerPlan("log", List.of());
        }
        J.ClassDeclaration.Kind.Type kind = owner.decl().getKind();
        if (kind == J.ClassDeclaration.Kind.Type.Interface || kind == J.ClassDeclaration.Kind.Type.Enum
                || kind == J.ClassDeclaration.Kind.Type.Annotation) {
            return new LoggerPlan(null, List.of());
        }
        SourceFileInfo f = owner.file();
        String text = f.text();
        if (lombok && preferredName.equals("log")) {
            int at = Pos.start(owner.decl());
            String indent = Text.indentAt(text, at);
            List<Edit> edits = new ArrayList<>();
            edits.add(Edit.insert(f.relPath(), at, "@Slf4j\n" + indent));
            edits.addAll(imports(f, "lombok.extern.slf4j.Slf4j"));
            return new LoggerPlan("log", edits);
        }
        int brace = Pos.start(owner.decl().getBody());
        if (brace < 0 || text.charAt(brace) != '{' || !Text.blank(text, brace + 1, Text.lineEnd(text, brace))) {
            return new LoggerPlan(null, List.of());
        }
        String indent = Text.indentAt(text, Pos.start(owner.decl())) + Text.indentUnit(text);
        int at = Text.nextLine(text, brace);
        boolean nextBlank = Text.blank(text, at, Text.lineEnd(text, at));
        String field = indent + "private static final Logger " + preferredName + " = LoggerFactory.getLogger("
                + owner.simpleName() + ".class);\n" + (nextBlank ? "" : "\n");
        List<Edit> edits = new ArrayList<>();
        edits.add(Edit.insert(f.relPath(), at, field));
        edits.addAll(imports(f, "org.slf4j.Logger", "org.slf4j.LoggerFactory"));
        return new LoggerPlan(preferredName, edits);
    }

    /** End offset of a statement including its ';' (when it directly follows). */
    public static int statementEnd(String text, J stmt) {
        int end = Pos.end(stmt);
        int i = end;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        return i < text.length() && text.charAt(i) == ';' ? i + 1 : end;
    }

    /** Range covering whole lines of a statement when it is alone on its line(s), else just the statement. */
    public static int[] statementLines(String text, J stmt) {
        int start = Pos.start(stmt);
        int end = statementEnd(text, stmt);
        int ls = Text.lineStart(text, start);
        int le = Text.lineEnd(text, end);
        String after = text.substring(end, le).strip();
        if (Text.blank(text, ls, start) && (after.isEmpty() || after.startsWith("//"))) {
            return new int[]{ls, Math.min(le + 1, text.length())};
        }
        return new int[]{start, end};
    }

    public static Edit deleteStatement(SourceFileInfo f, J stmt) {
        int[] r = statementLines(f.text(), stmt);
        return Edit.replace(f.relPath(), r[0], r[1], "");
    }

    /** Text of {@code [from, to)} with the given ranges cut out (ranges must lie inside and not overlap). */
    public static String cut(String text, int from, int to, List<int[]> ranges) {
        StringBuilder sb = new StringBuilder();
        int pos = from;
        List<int[]> sorted = new ArrayList<>(ranges);
        sorted.sort((a, b) -> Integer.compare(a[0], b[0]));
        for (int[] r : sorted) {
            if (r[0] < pos || r[1] > to) {
                continue;
            }
            sb.append(text, pos, r[0]);
            pos = r[1];
        }
        sb.append(text, pos, to);
        return sb.toString();
    }

    /**
     * Inner text of a block whose braces sit on their own lines: from the line after '{' to the line of '}'.
     * Returns null when the block is not laid out that way.
     */
    public static int[] blockInner(String text, J.Block block) {
        int open = Pos.start(block);
        int close = Pos.end(block) - 1;
        if (open < 0 || text.charAt(open) != '{' || text.charAt(close) != '}') {
            return null;
        }
        if (!Text.blankOrComment(text, open + 1, Text.lineEnd(text, open)) || !Text.blank(text, Text.lineStart(text, close), close)) {
            return null;
        }
        int innerStart = Text.nextLine(text, open);
        int innerEnd = Text.lineStart(text, close);
        return innerStart <= innerEnd ? new int[]{innerStart, innerEnd} : null;
    }
}
