package dev.logast.facts;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A relation between two nodes (#152): an entrypoint writes a table, a job runs after another, a service uses a secret.
 *
 * @param confidence 0..1, for {@link Provenance#INFERRED} (optional for {@link Provenance#LLM})
 * @param via        what made an inferred edge: a join rule or a company.yml entry
 * @param sources    what an LLM claim rests on: fact ids, glossary entries, page URLs
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({"from", "kind", "to", "env", "attrs", "provenance", "confidence", "via", "sources", "evidence"})
public record Edge(String from, String kind, String to, String env, Map<String, Object> attrs, Provenance provenance,
                   Double confidence, String via, List<String> sources, Evidence evidence) {
    public Edge {
        attrs = attrs == null ? Map.of() : new TreeMap<>(attrs);
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /** An edge the files prove. */
    public static Edge extracted(String from, String kind, String to, Map<String, Object> attrs, Evidence evidence) {
        return new Edge(from, kind, to, null, attrs, Provenance.EXTRACTED, null, null, null, evidence);
    }

    public Edge inEnv(String environment) {
        return new Edge(from, kind, to, environment, attrs, provenance, confidence, via, sources, evidence);
    }
}
