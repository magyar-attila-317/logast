package dev.logast.rules;

import dev.logast.policy.Policy;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Replaces banned synonyms with canonical domain terms (word boundaries, simple plurals, case kept). */
public final class Glossary {
    private final Policy policy;
    private final List<String> terms;

    public Glossary(Policy policy) {
        this.policy = policy;
        this.terms = policy.terms();
    }

    public String normalize(String text) {
        return normalize(text, null);
    }

    /** Replaces aliases with canonical terms; an area's own vocabulary takes precedence. */
    public String normalize(String text, String area) {
        String out = text;
        for (var e : policy.synonyms(area).entrySet()) {
            Pattern p = Pattern.compile("\\b(" + Pattern.quote(e.getKey()) + ")(s?)\\b", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(out);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String canonical = e.getValue();
                if (Character.isUpperCase(m.group(1).charAt(0))) {
                    canonical = Character.toUpperCase(canonical.charAt(0)) + canonical.substring(1);
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(canonical + m.group(2)));
            }
            m.appendTail(sb);
            out = sb.toString();
        }
        return out;
    }

    /** Banned words present in the text, as "word → canonical". */
    public List<String> violations(String text) {
        return violations(text, null);
    }

    public List<String> violations(String text, String area) {
        List<String> out = new ArrayList<>();
        for (var e : policy.synonyms(area).entrySet()) {
            if (Pattern.compile("\\b" + Pattern.quote(e.getKey()) + "s?\\b", Pattern.CASE_INSENSITIVE).matcher(text).find()) {
                out.add("'" + e.getKey() + "' → use '" + e.getValue() + "'");
            }
        }
        return out;
    }

    public List<String> terms() {
        return terms;
    }
}
