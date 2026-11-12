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
            if (n > 0 && n < line.length()) {
                min = Math.min(min, n);
            }
        }
        return min == Integer.MAX_VALUE || min > 8 ? "    " : " ".repeat(min);
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
}
