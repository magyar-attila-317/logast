package dev.logast;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spring's {@code ${key:default}} in configuration values and annotation attributes, resolved against a lookup (a
 * service's configuration). Three ways to treat a key the lookup does not know and that has no default, each used where
 * it fits: {@link #strict} gives up, {@link #keeping} keeps {@code ${key}}, {@link #nested} resolves values that hold
 * placeholders themselves and marks what stays unknown.
 */
public final class Placeholders {
    public static final Pattern PATTERN = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}");
    private static final int NESTED_DEPTH = 5;

    private Placeholders() {
    }

    /** Every placeholder resolved; null when one has neither a value nor a default, or its value holds a placeholder. */
    public static String strict(String text, Function<String, String> lookup) {
        if (text == null) {
            return null;
        }
        Matcher pm = PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (pm.find()) {
            String v = value(pm, lookup);
            if (v == null || v.contains("${")) {
                return null;
            }
            pm.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        pm.appendTail(sb);
        return sb.toString();
    }

    /** Every placeholder resolved that can be; one with neither a value nor a default stays {@code ${key}}. */
    public static String keeping(String text, Function<String, String> lookup) {
        Matcher pm = PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (pm.find()) {
            String v = value(pm, lookup);
            pm.appendReplacement(sb, Matcher.quoteReplacement(v != null ? v : "${" + pm.group(1).strip() + "}"));
        }
        pm.appendTail(sb);
        return sb.toString();
    }

    /** Values holding placeholders resolved in turn (a few levels deep); what stays unknown becomes {@code unknown}. */
    public static String nested(String text, Function<String, String> lookup, String unknown) {
        return nested(text, lookup, unknown, 0);
    }

    private static String nested(String text, Function<String, String> lookup, String unknown, int depth) {
        Matcher pm = PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (pm.find()) {
            String v = value(pm, lookup);
            v = v != null ? v : unknown;
            if (v.contains("${")) {
                v = depth < NESTED_DEPTH ? nested(v, lookup, unknown, depth + 1) : unknown;
            }
            pm.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        pm.appendTail(sb);
        return sb.toString();
    }

    /** The lookup's value for the key, else the placeholder's default; null when neither. */
    private static String value(Matcher pm, Function<String, String> lookup) {
        String v = lookup.apply(pm.group(1).strip());
        return v != null ? v : pm.group(2);
    }
}
