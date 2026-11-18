package dev.logast.rules;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads suppression comments. {@code // logast:ignore} silences every rule on its line and the next,
 * {@code // logast:ignore RULE [RULE …]} only the listed rules there, and {@code // logast:ignore-file RULE [RULE …]}
 * the listed rules in the whole file. Rule ids match exactly (whitespace or comma separated); the list ends at the
 * first word that is not a rule id, so a reason may follow. Source markers ({@code // logast: RULE ID}) are not
 * suppressions.
 */
final class Suppressions {
    private static final Pattern IGNORE = Pattern.compile("logast:ignore(-file)?(?![\\w-])");
    private static final Pattern RULE_ID = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Pattern SEPARATOR = Pattern.compile("[\\s,]+");

    private Suppressions() {
    }

    /** Whether a {@code logast:ignore} on this line silences {@code rule}. */
    static boolean lineIgnores(String line, String rule) {
        Matcher m = IGNORE.matcher(line);
        while (m.find()) {
            if (m.group(1) != null) {
                continue;
            }
            String rest = line.substring(m.end()).strip();
            Set<String> rules = rules(rest);
            if (rules.contains(rule) || rules.isEmpty() && blank(rest)) {
                return true;
            }
        }
        return false;
    }

    /** Rules a {@code logast:ignore-file RULE …} anywhere in {@code text} silences for the whole file. */
    static Set<String> fileIgnores(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (!text.contains("logast:ignore-file")) {
            return out;
        }
        Matcher m = IGNORE.matcher(text);
        while (m.find()) {
            if (m.group(1) != null) {
                int eol = text.indexOf('\n', m.end());
                out.addAll(rules(text.substring(m.end(), eol < 0 ? text.length() : eol).strip()));
            }
        }
        return out;
    }

    /**
     * #56: rule ids in {@code logast:ignore} / {@code logast:ignore-file} comments of {@code text} that are no rule
     * (a typo silences nothing): "line: ID".
     */
    static List<String> unknownIds(String text) {
        List<String> out = new ArrayList<>();
        Set<String> known = Rules.all();
        Matcher m = IGNORE.matcher(text);
        while (m.find()) {
            int eol = text.indexOf('\n', m.end());
            for (String id : rules(text.substring(m.end(), eol < 0 ? text.length() : eol).strip())) {
                if (!known.contains(id)) {
                    int line = (int) text.substring(0, m.start()).chars().filter(c -> c == '\n').count() + 1;
                    out.add(line + ": " + id);
                }
            }
        }
        return out;
    }

    /** The leading rule ids of {@code rest}. */
    private static Set<String> rules(String rest) {
        Set<String> out = new LinkedHashSet<>();
        for (String token : SEPARATOR.split(rest)) {
            if (!RULE_ID.matcher(token).matches()) {
                break;
            }
            out.add(token);
        }
        return out;
    }

    /** Nothing after a bare ignore but the end of its comment (or another comment): it silences every rule. */
    private static boolean blank(String rest) {
        return rest.isEmpty() || rest.startsWith("*/") || rest.startsWith("//");
    }
}
