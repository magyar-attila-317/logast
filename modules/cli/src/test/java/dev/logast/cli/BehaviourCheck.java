package dev.logast.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * #7 ("behaviour preserved"): a Java source reduced to the statements that are not logging, MDC, metrics or
 * observation code, to compare a file before and after a migration with the logging pack. Used by E2eGoldenTest and
 * CorpusRun (a class of its own: E2eGoldenTest needs the e2e settings to load).
 */
final class BehaviourCheck {
    private BehaviourCheck() {
    }

    private static final java.util.regex.Pattern OBSERVABILITY = java.util.regex.Pattern.compile(
            "\\b(log|logger|LOG|LOGGER|Logger|LoggerFactory|MDC|MdcScope|ObservedContext|Outcomes|Observation|"
                    + "ObservationRegistry|Metrics|MeterRegistry|Counter|Timer|Tracer|Span)\\b|\\b\\w*(log|LOG)\\w*\\."
                    + "(trace|debug|info|warn|error|at\\w+)\\(|^\\.(mdc|quiet|debugOnSuccess|failureLoggedByCaller|describe|tag|"
                    + "contextualName|run|call)\\(|^\\)$|^\\)\\)$");
    /** Annotations that only switch observability on: removed from a statement, which stays. */
    private static final java.util.regex.Pattern OBSERVABILITY_ANNOTATION = java.util.regex.Pattern.compile("@(Slf4j|Observed|NewSpan|Timed|Counted)\\b(\\([^)]*\\))?\\s*");
    /** {@code T name = expr} (a local declaration). */
    private static final java.util.regex.Pattern LOCAL = java.util.regex.Pattern.compile("^(?:final )?[\\w<>\\[\\], ?.]+? (\\w+) = (.+)$");

    /**
     * The statements of a Java source that are not observability code, in order: comments, imports, the package line,
     * blank lines and braces dropped, string literals kept as written.
     */
    static List<String> behaviour(String java) {
        String text = withoutComments(java).replaceAll("(?m)^\\s*(import|package) .*$", "");
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (!inString && (c == ';' || c == '{' || c == '}')) {
                add(out, cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        add(out, cur.toString());
        return inlineLocals(out);
    }

    /**
     * The source without its comments; a {@code //} or {@code /*} inside a string or char literal is text
     * ({@code "http://loyalty"}), not a comment.
     */
    static String withoutComments(String java) {
        StringBuilder out = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < java.length(); i++) {
            char c = java.charAt(i);
            char next = i + 1 < java.length() ? java.charAt(i + 1) : 0;
            if (quote != 0) {
                out.append(c);
                if (c == '\\' && next != 0) {
                    out.append(next);
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '/' && next == '/') {
                while (i < java.length() && java.charAt(i) != '\n') {
                    i++;
                }
                out.append('\n');
            } else if (c == '/' && next == '*') {
                int end = java.indexOf("*/", i + 2);
                i = end < 0 ? java.length() : end + 1;
            } else {
                if (c == '"' || c == '\'') {
                    quote = c;
                }
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Locals with one use or none, folded into where they are used (both sides alike): logast captures a value to log it
     * ({@code int rows = jdbc.update(..)}, {@code var ids = repository.findOpen(); for (.. : ids)}), which is the same
     * behaviour as before.
     */
    private static List<String> inlineLocals(List<String> statements) {
        List<String> out = new ArrayList<>(statements);
        for (int i = 0; i < out.size(); i++) {
            java.util.regex.Matcher m = LOCAL.matcher(out.get(i));
            if (!m.matches()) {
                continue;
            }
            java.util.regex.Pattern use = java.util.regex.Pattern.compile("\\b" + m.group(1) + "\\b");
            List<Integer> uses = new ArrayList<>();
            for (int j = i + 1; j < out.size(); j++) {
                if (use.matcher(out.get(j)).find()) {
                    uses.add(j);
                }
            }
            if (uses.size() > 1) {
                continue;
            }
            if (uses.size() == 1) {
                int j = uses.getFirst();
                out.set(j, use.matcher(out.get(j)).replaceAll(java.util.regex.Matcher.quoteReplacement(m.group(2))));
                out.remove(i);
            } else {
                out.set(i, m.group(2));
            }
            i = -1;   // start over: a folded local may make another one foldable
        }
        return out;
    }

    private static void add(List<String> out, String statement) {
        String s = OBSERVABILITY_ANNOTATION.matcher(statement.replaceAll("\\s+", " ")).replaceAll("").strip();
        if (!s.isEmpty() && !s.equals(")") && !OBSERVABILITY.matcher(s).find() && !s.startsWith("try (var mdc")
                && !s.equals("try") && !s.equals("finally") && !s.matches("\\) -> ?|\\(\\) ->")) {
            out.add(s);
        }
    }

}
