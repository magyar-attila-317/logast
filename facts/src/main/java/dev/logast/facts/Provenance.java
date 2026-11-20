package dev.logast.facts;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How an edge is known (#152): {@code extracted} the files prove it; {@code inferred} a join or a company.yml entry made
 * it ({@code via} names which, with a {@code confidence}); {@code llm} an interpretation, with its {@code sources}.
 */
public enum Provenance {
    EXTRACTED, INFERRED, LLM;

    @JsonValue
    public String json() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static Provenance of(String s) {
        for (Provenance p : values()) {
            if (p.json().equals(s)) {
                return p;
            }
        }
        return null;
    }
}
