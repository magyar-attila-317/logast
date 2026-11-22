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
 * @param generated    #231: the text logast writes, marked {@code // logast:wording}, when nobody wrote one; null: the
 *                     order waits for a text
 */
public record Slot(String describe, List<String> keys, List<String> allowedTerms, String original,
                   String event, String pattern, String generated) {
    /** Marks a log line whose wording logast generated: MESSAGE_SUGGESTED asks for domain words. */
    public static final String WORDING_MARKER = "// logast:wording";

    public Slot(String describe, List<String> keys, List<String> allowedTerms, String original) {
        this(describe, keys, allowedTerms, original, null, null, null);
    }

    public Slot(String describe, List<String> keys, List<String> allowedTerms, String original, String event, String pattern) {
        this(describe, keys, allowedTerms, original, event, pattern, null);
    }

    /** The same slot with a generated text (#231). */
    public Slot withGenerated(String text) {
        return new Slot(describe, keys, allowedTerms, original, event, pattern, text);
    }
}
