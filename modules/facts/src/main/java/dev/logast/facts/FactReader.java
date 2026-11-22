package dev.logast.facts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Reads and validates a facts file (#152). Every rule of docs/facts.md is checked line by line; a line that breaks one
 * is reported with its number and left out, the others are read.
 */
public final class FactReader {
    private FactReader() {
    }

    public record Problem(int line, String message) {
        @Override
        public String toString() {
            return "line " + line + ": " + message;
        }
    }

    public record Result(Header header, List<Node> nodes, List<Edge> edges, List<Problem> problems) {
        public boolean ok() {
            return problems.isEmpty();
        }
    }

    public static Result read(Reader in) throws IOException {
        BufferedReader r = new BufferedReader(in);
        Header header = null;
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        int n = 0;
        boolean first = true;
        for (String text; (text = r.readLine()) != null; ) {
            n++;
            if (text.isBlank()) {
                continue;
            }
            List<String> errors = new ArrayList<>();
            JsonNode line;
            try {
                line = FactWriter.JSON.readTree(text);
            } catch (JsonProcessingException e) {
                problems.add(new Problem(n, "not JSON: " + e.getOriginalMessage()));
                first = false;
                continue;
            }
            if (!line.isObject() || line.size() != 1) {
                problems.add(new Problem(n, "a line is one object with exactly one key: header, node or edge"));
                first = false;
                continue;
            }
            String key = line.fieldNames().next();
            JsonNode body = line.get(key);
            switch (key) {
                case "header" -> {
                    if (!first) {
                        errors.add("the header must be the first line, and only once");
                    }
                    Header h = header(body, errors);
                    if (errors.isEmpty() && first) {
                        header = h;
                    }
                }
                case "node" -> {
                    if (first) {
                        errors.add("the first line must be the header");
                    }
                    Node node = node(body, header, errors);
                    if (errors.isEmpty()) {
                        nodes.add(node);
                    }
                }
                case "edge" -> {
                    if (first) {
                        errors.add("the first line must be the header");
                    }
                    Edge edge = edge(body, header, errors);
                    if (errors.isEmpty()) {
                        edges.add(edge);
                    }
                }
                default -> errors.add("unknown line type '" + key + "' (header, node or edge)");
            }
            first = false;
            int at = n;
            errors.forEach(e -> problems.add(new Problem(at, e)));
        }
        if (n == 0 || (header == null && problems.isEmpty())) {
            problems.add(new Problem(Math.max(n, 1), "no header line"));
        }
        return new Result(header, List.copyOf(nodes), List.copyOf(edges), List.copyOf(problems));
    }

    private static Header header(JsonNode b, List<String> errors) {
        if (!fields(b, "header", FactFormat.HEADER_FIELDS, FactFormat.HEADER_REQUIRED, errors)) {
            return null;
        }
        String schema = text(b, "schema");
        if (!FactFormat.SCHEMA.equals(schema)) {
            errors.add("schema '" + schema + "' is not " + FactFormat.SCHEMA);
        }
        return new Header(schema, text(b, "extractor"), text(b, "extractorVersion"), text(b, "repo"), text(b, "commit"),
                text(b, "generatedAt"));
    }

    private static Node node(JsonNode b, Header header, List<String> errors) {
        if (!fields(b, "node", FactFormat.NODE_FIELDS, FactFormat.NODE_REQUIRED, errors)) {
            return null;
        }
        String id = text(b, "id");
        String kind = text(b, "kind");
        kind(kind, "node", errors);
        String idKind = FactFormat.kindOf(id);
        if (idKind == null) {
            errors.add("id '" + id + "' is not <kind>:<name>");
        } else if (!idKind.equals(kind)) {
            errors.add("id '" + id + "' does not start with its kind '" + kind + ":'");
        }
        String env = env(b, errors);
        Map<String, Object> attrs = attrs(b, errors);
        Evidence ev = evidence(b.get("evidence"), header, errors);
        return new Node(id, kind, env, attrs, ev);
    }

    private static Edge edge(JsonNode b, Header header, List<String> errors) {
        if (!fields(b, "edge", FactFormat.EDGE_FIELDS, FactFormat.EDGE_REQUIRED, errors)) {
            return null;
        }
        String from = text(b, "from");
        String to = text(b, "to");
        String kind = text(b, "kind");
        for (String end : new String[]{from, to}) {
            if (FactFormat.kindOf(end) == null) {
                errors.add("'" + end + "' is not an id (<kind>:<name>)");
            }
        }
        kind(kind, "edge", errors);
        String env = env(b, errors);
        Map<String, Object> attrs = attrs(b, errors);
        Provenance p = Provenance.of(text(b, "provenance"));
        Double confidence = b.hasNonNull("confidence") ? b.get("confidence").asDouble(Double.NaN) : null;
        if (b.hasNonNull("confidence") && (!b.get("confidence").isNumber() || confidence <= 0 || confidence > 1)) {
            errors.add("confidence must be a number above 0, at most 1");
        }
        String via = text(b, "via");
        List<String> sources = new ArrayList<>();
        if (b.has("sources")) {
            if (!b.get("sources").isArray()) {
                errors.add("sources must be a list");
            } else {
                b.get("sources").forEach(s -> sources.add(s.asText()));
            }
        }
        JsonNode evNode = b.get("evidence");
        if (p == null) {
            errors.add("provenance must be extracted, inferred or llm, not '" + text(b, "provenance") + "'");
        } else {
            switch (p) {
                case EXTRACTED -> {
                    if (evNode == null) {
                        errors.add("an extracted edge needs evidence");
                    }
                    if (confidence != null || via != null || !sources.isEmpty()) {
                        errors.add("an extracted edge has no confidence, via or sources: the files prove it");
                    }
                }
                case INFERRED -> {
                    if (confidence == null || via == null || via.isBlank()) {
                        errors.add("an inferred edge needs a confidence and via (the rule or entry that made it)");
                    }
                }
                case LLM -> {
                    if (sources.isEmpty()) {
                        errors.add("an llm edge needs sources (facts, glossary entries, pages it rests on)");
                    }
                    if (via != null) {
                        errors.add("via is for inferred edges");
                    }
                }
            }
        }
        Evidence ev = evNode == null ? null : evidence(evNode, header, errors);
        return new Edge(from, kind, to, env, attrs, p, confidence, via, sources, ev);
    }

    private static Evidence evidence(JsonNode e, Header header, List<String> errors) {
        if (e == null || !e.isObject()) {
            errors.add("evidence must be an object with at least a file");
            return null;
        }
        if (!fields(e, "evidence", FactFormat.EVIDENCE_FIELDS, FactFormat.EVIDENCE_REQUIRED, errors)) {
            return null;
        }
        Integer line = null;
        if (e.has("line")) {
            if (!e.get("line").canConvertToInt() || !e.get("line").isIntegralNumber() || e.get("line").asInt() < 1) {
                errors.add("evidence line must be a whole number from 1");
            } else {
                line = e.get("line").asInt();
            }
        }
        String repo = text(e, "repo");
        if (repo == null && (header == null || header.repo() == null)) {
            errors.add("evidence has no repo, and there is no header to take it from");
        }
        return new Evidence(repo, text(e, "root"), text(e, "file"), line, text(e, "commit"));
    }

    /** Unknown fields are errors (a typo would otherwise be lost); required ones must be non-empty. */
    private static boolean fields(JsonNode b, String what, List<String> known, List<String> required, List<String> errors) {
        if (b == null || !b.isObject()) {
            errors.add(what + " must be an object");
            return false;
        }
        int before = errors.size();
        for (Iterator<String> it = b.fieldNames(); it.hasNext(); ) {
            String f = it.next();
            if (!known.contains(f)) {
                errors.add(what + " has an unknown field '" + f + "'");
            }
        }
        for (String f : required) {
            JsonNode v = b.get(f);
            if (v == null || v.isNull() || (v.isTextual() && v.asText().isBlank())) {
                errors.add(what + " needs '" + f + "'");
            }
        }
        return errors.size() == before;
    }

    private static void kind(String kind, String what, List<String> errors) {
        if (kind == null || !FactFormat.KIND.matcher(kind).matches()) {
            errors.add(what + " kind '" + kind + "' is not lower case letters, digits and dashes");
        }
    }

    private static String env(JsonNode b, List<String> errors) {
        String env = text(b, "env");
        if (env != null && !FactFormat.ENV.matcher(env).matches()) {
            errors.add("env '" + env + "' is not lower case letters, digits, '.', '_' and '-'");
        }
        return env;
    }

    private static Map<String, Object> attrs(JsonNode b, List<String> errors) {
        JsonNode a = b.get("attrs");
        if (a == null || a.isNull()) {
            return Map.of();
        }
        if (!a.isObject()) {
            errors.add("attrs must be an object");
            return Map.of();
        }
        return FactWriter.JSON.convertValue(a, new TypeReference<Map<String, Object>>() {
        });
    }

    private static String text(JsonNode b, String field) {
        JsonNode v = b.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
