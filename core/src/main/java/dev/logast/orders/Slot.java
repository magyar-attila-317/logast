package dev.logast.orders;

import java.util.List;

/**
 * Text the LLM must provide. Rendered into edits wherever {@code {{NAME}}} appears.
 *
 * @param describe     what the text should say
 * @param keys         the {@code [k=v]} keys the tool appends itself (the text must not repeat them)
 * @param allowedTerms domain glossary terms to prefer
 * @param original     current message text when rewording an existing log line
 * @param event        lifecycle event the text must express (policy.phrasing), e.g. "failed"; null = free
 * @param pattern      canonical form of that event, e.g. "{subject} failed"
 */
public record Slot(String describe, List<String> keys, List<String> allowedTerms, String original,
                   String event, String pattern) {
    public Slot(String describe, List<String> keys, List<String> allowedTerms, String original) {
        this(describe, keys, allowedTerms, original, null, null);
    }
}
