package dev.logast.facts;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Map;
import java.util.TreeMap;

/**
 * A thing that exists (#152): a service, an endpoint, a table, a topic, a secret. Its {@code id} is
 * {@code <kind>:<name>} and stable across runs, so edges, approvals and other extractors can refer to it.
 *
 * @param env null when it holds in every environment, else the environment ("dev", "prod")
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({"id", "kind", "env", "attrs", "evidence"})
public record Node(String id, String kind, String env, Map<String, Object> attrs, Evidence evidence) {
    public Node {
        attrs = attrs == null ? Map.of() : new TreeMap<>(attrs);   // sorted: the same facts write the same bytes
    }

    public static Node of(String kind, String name, Map<String, Object> attrs, Evidence evidence) {
        return new Node(FactFormat.id(kind, name), kind, null, attrs, evidence);
    }

    public Node inEnv(String environment) {
        return new Node(id, kind, environment, attrs, evidence);
    }
}
