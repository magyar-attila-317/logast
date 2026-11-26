package dev.logast.rules;

/** Line/indent helpers over raw source text. Lines end with '\n'. */
public final class Text {
    private Text() {
    }

    public static int lineStart(String s, int offset) {
        return s.lastIndexOf('\n', offset - 1) + 1;
    }

    /** Offset of the '\n' ending the line containing {@code offset} (or text length). */
    public static int lineEnd(String s, int offset) {
        int i = s.indexOf('\n', offset);
        return i < 0 ? s.length() : i;
    }

    /** Offset just past the line containing {@code offset}, including its newline. */
    public static int nextLine(String s, int offset) {
        return Math.min(lineEnd(s, offset) + 1, s.length());
    }

    /**
     * #241: the indent of a block's statements: its first statement's, else its owner's line (the method's name, the
     * {@code try}) plus one unit. Never the line of the opening brace, which is a wrapped parameter line when a method's
     * parameters wrap.
     */
    public static String blockIndent(String s, org.openrewrite.java.tree.J.Block block, int ownerStart) {
        if (!block.getStatements().isEmpty()) {
            int first = dev.logast.parse.Pos.start(block.getStatements().getFirst());
            if (lineStart(s, first) > lineStart(s, dev.logast.parse.Pos.start(block))) {
                return indentAt(s, first);
            }
        }
        return indentAt(s, ownerStart) + indentUnit(s);
    }

    public static String indentAt(String s, int offset) {
        int ls = lineStart(s, offset);
        int i = ls;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
        return s.substring(ls, i);
    }

    /** Only whitespace between {@code from} and {@code to}. */
    public static boolean blank(String s, int from, int to) {
        return from >= to || s.substring(from, to).isBlank();
    }

    /** Only whitespace, optionally followed by a {@code //} comment, between {@code from} and {@code to}. */
    public static boolean blankOrComment(String s, int from, int to) {
        String t = from >= to ? "" : s.substring(from, to).strip();
        return t.isEmpty() || t.startsWith("//");
    }

    /** Indent unit used by the file: a tab, or the smallest positive space indent (default 4). */
    public static String indentUnit(String s) {
        int min = Integer.MAX_VALUE;
        for (String line : s.split("\n")) {
            if (line.startsWith("\t")) {
                return "\t";
            }
            int n = 0;
            while (n < line.length() && line.charAt(n) == ' ') {
                n++;
            }
            if (n > 0 && n < line.length() && line.charAt(n) != '*') {   // " * text" in a Javadoc is not indentation
                min = Math.min(min, n);
            }
        }
        return min == Integer.MAX_VALUE || min > 8 ? "    " : " ".repeat(min);
    }

    public static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** Removes one {@code unit} of indentation from every line that has it. */
    public static String dedent(String block, String unit) {
        StringBuilder sb = new StringBuilder();
        for (String line : block.split("\n", -1)) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(line.startsWith(unit) ? line.substring(unit.length()) : line);
        }
        return sb.toString();
    }

    /** Prefixes every non-blank line with {@code add}. */
    public static String reindent(String block, String add) {
        StringBuilder sb = new StringBuilder();
        for (String line : block.split("\n", -1)) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(line.isBlank() ? line : add + line);
        }
        return sb.toString();
    }

    public static String javaString(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public static String camel(String key) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : key.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            } else {
                up = !sb.isEmpty();
            }
        }
        return sb.toString();
    }

    public static String kebab(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(java.util.Locale.ROOT);
    }

    public static String oneLine(String s, int max) {
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }

    /**
     * #334: the comments right above {@code offset} (a declaration's start, its annotations included): a block or
     * javadoc comment and/or consecutive line comments, separated from it only by whitespace; "" when there are none.
     */
    static String commentBefore(String text, int offset) {
        StringBuilder out = new StringBuilder();
        int at = Math.min(Math.max(offset, 0), text.length());
        while (true) {
            int end = at;
            while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
                end--;
            }
            if (end >= 2 && text.startsWith("*/", end - 2)) {
                int open = text.lastIndexOf("/*", end - 2);
                if (open < 0) {
                    return out.toString();
                }
                out.insert(0, text, open, end);
                at = open;
                continue;
            }
            int lineStart = text.lastIndexOf('\n', Math.max(end - 1, 0)) + 1;
            String line = text.substring(lineStart, end).strip();
            if (end > 0 && line.startsWith("//")) {
                out.insert(0, line + "\n");
                at = lineStart;
                continue;
            }
            return out.toString();
        }
    }
}
