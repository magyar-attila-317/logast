package dev.logast.facts;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules of the facts format (#152), shared by the writer and the validator; docs/facts.md describes them and
 * {@code kb-facts-0.schema.json} repeats them for producers in other languages.
 */
public final class FactFormat {
    private FactFormat() {
    }

    /** The schema of this format; a later, incompatible one is {@code kb-facts/1}. Additions keep {@code /0}. */
    public static final String SCHEMA = "kb-facts/0";

    /** A kind: lower case, digits and dashes ("endpoint", "uses-secret"). */
    static final Pattern KIND = Pattern.compile("[a-z][a-z0-9-]*");
    /** An environment: "dev", "prod", "eu-west.prod". */
    static final Pattern ENV = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    /** An id: {@code <kind>:<name>}, the name any text without control characters. */
    static final Pattern ID = Pattern.compile("([a-z][a-z0-9-]*):([^\\p{Cntrl}]+)");

    static final List<String> HEADER_FIELDS = List.of("schema", "extractor", "extractorVersion", "repo", "commit", "generatedAt");
    static final List<String> HEADER_REQUIRED = List.of("schema", "extractor", "repo");
    static final List<String> NODE_FIELDS = List.of("id", "kind", "env", "attrs", "evidence");
    static final List<String> NODE_REQUIRED = List.of("id", "kind", "evidence");
    static final List<String> EDGE_FIELDS = List.of("from", "kind", "to", "env", "attrs", "provenance", "confidence", "via", "sources", "evidence");
    static final List<String> EDGE_REQUIRED = List.of("from", "kind", "to", "provenance");
    static final List<String> EVIDENCE_FIELDS = List.of("repo", "root", "file", "line", "commit");
    static final List<String> EVIDENCE_REQUIRED = List.of("file");

    /**
     * The kinds extractors share. Any other kind that matches {@link #KIND} is valid too (an extension's own), but these
     * are the ones joins and tools rely on: docs/facts.md says what each means and how its name is built.
     */
    public static final Set<String> CORE_NODE_KINDS = Set.of(
            "service", "library", "endpoint", "listener", "job", "call", "bean", "profile", "property", "table",
            "column", "row", "topic", "file-location", "secret", "deployment", "image", "chart", "workflow", "resource",
            "term");
    public static final Set<String> CORE_EDGE_KINDS = Set.of(
            "has", "calls", "reads", "writes", "publishes", "consumes", "runs", "after", "active-with", "uses-property",
            "uses-secret", "depends-on", "deploys", "maps", "alias", "defines");

    /** {@code <kind>:<name>}. */
    public static String id(String kind, String name) {
        if (kind == null || !KIND.matcher(kind).matches()) {
            throw new IllegalArgumentException("not a kind: " + kind);
        }
        if (name == null || name.isBlank() || !ID.matcher(kind + ":" + name).matches()) {
            throw new IllegalArgumentException("not a name for " + kind + ": " + name);
        }
        return kind + ":" + name;
    }

    /** The kind part of an id, or null when it is not one. */
    public static String kindOf(String id) {
        var m = id == null ? null : ID.matcher(id);
        return m != null && m.matches() ? m.group(1) : null;
    }
}
