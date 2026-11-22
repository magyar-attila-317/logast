package dev.logast.rules;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a rule finds and how it is fixed, in words (#142), read from the rule tables of the pack pages
 * (docs/packs/*.md, shipped as resources under {@code rule-docs/}): one source for the docs and the fleet report.
 */
public final class RuleHelp {
    /** @param fix starts with AUTO, NEEDS TEXT or REPORT when the docs say so */
    public record Help(String rule, String label, String severity, String finds, String fix) {
    }

    private static final List<String> PACKS = List.of("logging", "tracing", "metrics", "resilience", "errors", "audit");
    private static final java.util.regex.Pattern RULE_ID = java.util.regex.Pattern.compile("\\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\\b");
    private static volatile Map<String, Help> all;

    private RuleHelp() {
    }

    /** Every rule with a row on a pack page; a rule without one gets only its label. */
    public static Map<String, Help> all() {
        Map<String, Help> m = all;
        if (m == null) {
            m = new TreeMap<>();
            for (String pack : PACKS) {
                try (InputStream in = RuleHelp.class.getResourceAsStream("/rule-docs/" + pack + ".md")) {
                    if (in != null) {
                        parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), m);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            all = m;
        }
        return m;
    }

    public static Help of(String rule) {
        Help h = all().get(rule);
        return h != null ? h : new Help(rule, RuleLabels.of(rule), null, null, null);
    }

    static void parse(String markdown, Map<String, Help> into) {
        for (String line : markdown.split("\n")) {
            if (!line.startsWith("| ")) {
                continue;
            }
            List<String> cells = cells(line);
            if (cells.size() < 4) {
                continue;
            }
            // one row can hold several rules: "A / B", "A, B (shared with logging)"
            java.util.regex.Matcher id = RULE_ID.matcher(cells.get(0).replaceAll("\\(.*?\\)", ""));
            while (id.find()) {
                into.putIfAbsent(id.group(), new Help(id.group(), RuleLabels.of(id.group()), cells.get(1),
                        plain(cells.get(2)), plain(cells.get(3))));
            }
        }
    }

    /** The cells of a table row; {@code \|} is a pipe inside a cell. */
    private static List<String> cells(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        String body = line.strip();
        for (int i = 1; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length() && body.charAt(i + 1) == '|') {
                cell.append('|');
                i++;
            } else if (c == '|') {
                out.add(cell.toString().strip());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        return out;
    }

    /** Markdown to plain text: links keep their text, code and emphasis marks go. */
    static String plain(String md) {
        return md.replaceAll("\\[([^\\]]*)]\\([^)]*\\)", "$1").replace("`", "").replace("**", "").strip();
    }
}
