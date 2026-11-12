package dev.logast.rules;

import dev.logast.parse.Pos;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The message of a log call in SLF4J placeholder form, plus helpers for the house format
 * {@code Text [key1={}, key2={}]}.
 */
public final class MessageTemplate {
    private static final Pattern COMPLIANT = Pattern.compile("^(.*?)(?: \\[((?:[\\w.\\-]+=\\{\\})(?:, [\\w.\\-]+=\\{\\})*)])?$");
    private static final Pattern PREP = Pattern.compile("(?i)\\s+(for|of|to|with|from|by|on|in)(\\s+(the|a|an))?\\s*$");

    /**
     * @param raw       message text with {@code {}} placeholders, Java escapes preserved
     * @param inline    expressions that were concatenated/formatted into the message, in placeholder order
     * @param converted the message was built with {@code +} or {@code String.format}
     */
    public record Parsed(String raw, List<Expression> inline, boolean converted) {
        public int placeholders() {
            return count(raw);
        }
    }

    private MessageTemplate() {
    }

    /** Null when the template is not analysable (a variable, a constant, a text block...). */
    public static Parsed parse(Expression template) {
        if (template instanceof J.Literal l) {
            String raw = literalRaw(l);
            return raw == null ? null : new Parsed(raw, List.of(), false);
        }
        if (template instanceof J.Binary b && b.getOperator() == J.Binary.Type.Addition && TypeUtils.isString(b.getType())) {
            StringBuilder sb = new StringBuilder();
            List<Expression> inline = new ArrayList<>();
            if (!flatten(b, sb, inline)) {
                return null;
            }
            // "a" + "b" split over lines is a compile-time constant, not runtime concatenation
            return new Parsed(sb.toString(), inline, !inline.isEmpty());
        }
        if (template instanceof J.MethodInvocation mi && mi.getSimpleName().equals("format")
                && mi.getMethodType() != null && TypeUtils.isOfClassType(mi.getMethodType().getDeclaringType(), "java.lang.String")) {
            List<Expression> args = mi.getArguments();
            if (args.isEmpty() || !(args.getFirst() instanceof J.Literal fmt)) {
                return null;
            }
            String raw = literalRaw(fmt);
            if (raw == null || Pattern.compile("%(?![sd%])").matcher(raw).find()) {
                return null;
            }
            return new Parsed(raw.replace("%s", "{}").replace("%d", "{}").replace("%%", "%"),
                    List.copyOf(args.subList(1, args.size())), true);
        }
        return null;
    }

    private static boolean flatten(Expression e, StringBuilder sb, List<Expression> inline) {
        if (e instanceof J.Binary b && b.getOperator() == J.Binary.Type.Addition && TypeUtils.isString(b.getType())) {
            return flatten(b.getLeft(), sb, inline) && flatten(b.getRight(), sb, inline);
        }
        if (e instanceof J.Literal l && l.getType() == JavaType.Primitive.String) {
            String raw = literalRaw(l);
            if (raw == null) {
                return false;
            }
            sb.append(raw);
            return true;
        }
        sb.append("{}");
        inline.add(e);
        return true;
    }

    private static String literalRaw(J.Literal l) {
        String src = l.getValueSource();
        if (src == null || !src.startsWith("\"") || src.startsWith("\"\"\"") || !(l.getValue() instanceof String)) {
            return null;
        }
        return src.substring(1, src.length() - 1);
    }

    public static int count(String raw) {
        int n = 0;
        for (int i = raw.indexOf("{}"); i >= 0; i = raw.indexOf("{}", i + 2)) {
            if (i == 0 || raw.charAt(i - 1) != '\\') {
                n++;
            }
        }
        return n;
    }

    private static final Pattern NAMED = Pattern.compile("([\\w.\\-]+)=\\{}");
    private static final Pattern NAMED_FRAGMENT = Pattern.compile("\\s*[,;]?\\s*\\b[\\w.\\-]+=\\{}");

    /** Per placeholder, the name written in front of it ({@code device={}} → "device"), else null. */
    public static List<String> placeholderNames(String raw) {
        List<String> out = new ArrayList<>();
        int i = raw.indexOf("{}");
        while (i >= 0) {
            Matcher m = Pattern.compile("([\\w.\\-]+)=$").matcher(raw.substring(0, i));
            out.add(m.find() ? m.group(1) : null);
            i = raw.indexOf("{}", i + 2);
        }
        return out;
    }

    /** Removes {@code name={}} fragments (and their separators); unnamed placeholders stay. */
    public static String removeNamedPlaceholders(String raw) {
        if (!NAMED.matcher(raw).find() || compliantKeys(raw) != null) {
            return raw;
        }
        return NAMED_FRAGMENT.matcher(raw).replaceAll("").replaceAll("[\\s,;:]+$", "").trim();
    }

    /** Keys of an already compliant message, or null when it is not in house format. */
    public static List<String> compliantKeys(String raw) {
        Matcher m = COMPLIANT.matcher(raw);
        if (!m.matches() || m.group(1).contains("{}")) {
            return null;
        }
        if (m.group(2) == null) {
            return List.of();
        }
        return Arrays.stream(m.group(2).split(", ")).map(kv -> kv.substring(0, kv.indexOf('='))).toList();
    }

    public static String compliantBase(String raw) {
        Matcher m = COMPLIANT.matcher(raw);
        return m.matches() ? m.group(1) : raw;
    }

    /** Placeholders only at the end (separated by punctuation) so they can move into the suffix mechanically. */
    public static boolean placeholdersTrailing(String raw) {
        String[] parts = raw.split("\\{}", -1);
        for (int i = 1; i < parts.length - 1; i++) {
            if (!parts[i].matches("[\\s,;:=/|\\-]*")) {
                return false;
            }
        }
        return parts.length == 1 || parts[parts.length - 1].matches("[\\s.,;:!)\\]]*");
    }

    /** Message text with placeholders removed and dangling separators trimmed. */
    public static String stripPlaceholders(String raw) {
        String base = raw.replace("{}", "");
        return trimTail(base);
    }

    private static String trimTail(String s) {
        String t = s.replaceAll("[\\s,;:=/|\\-]+$", "");
        return t.replaceAll("\\s+", " ").trim();
    }

    /**
     * Drops a trailing "for order" / "to" phrase whose noun belongs to a value that moved out of the
     * message (into MDC or the suffix), e.g. "Refund failed for order" → "Refund failed".
     */
    public static String stripTrailingPhrase(String base, Collection<String> keyStems) {
        String t = base;
        for (String stem : keyStems) {
            Pattern p = Pattern.compile("(?i)\\s+(for|of|to|with|from|by|on|in)(\\s+(the|a|an))?\\s+" + Pattern.quote(stem) + "s?\\s*$");
            t = p.matcher(t).replaceAll("");
        }
        t = PREP.matcher(t).replaceAll("");
        return trimTail(t);
    }

    /** "order.id" → "order", "shipmentId" → "shipment", "email" → "email". */
    public static String stem(String key) {
        String s = key.contains(".") ? key.substring(0, key.indexOf('.')) : key;
        return s.replaceAll("(Id|Name|Number|Code|Key)$", "");
    }

    public static String suffix(List<String> keys) {
        return keys.isEmpty() ? "" : " [" + String.join(", ", keys.stream().map(k -> k + "={}").toList()) + "]";
    }

    public static int start(Expression e) {
        return Pos.start(e);
    }
}
