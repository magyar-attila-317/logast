package dev.logast.kb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.logast.facts.Edge;
import dev.logast.facts.Evidence;
import dev.logast.facts.FactFormat;
import dev.logast.facts.FactSink;
import dev.logast.facts.Header;
import dev.logast.facts.Node;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * #158: company-specific facts from a mapping file, without code: each rule matches facts (a node kind, an id prefix,
 * attribute values), may look up other nodes by id, and writes new nodes and edges from templates with
 * {@code {attribute}} placeholders. The matched fact's environment and evidence carry over; a template with a
 * placeholder that has no value is skipped, not written half-filled.
 *
 * <pre>
 * mappings:
 *   - name: jobs
 *     match: {kind: row, idPrefix: "row:orchestration.job_definition:"}
 *     nodes:
 *       - {id: "job:{service}:{name}", kind: job, attrs: {schedule: "{cron}", scheduleFormat: unix}}
 *     edges:
 *       - {from: "service:{service}", kind: has, to: "job:{service}:{name}"}
 *       - {from: "job:{service}:{name}", kind: runs, to: "profile:{service}:{profile}"}
 *   - name: pipeline
 *     match: {kind: row, idPrefix: "row:orchestration.pipeline_step:"}
 *     lookup:
 *       - {as: job, id: "row:orchestration.job_definition:{job_id}"}
 *       - {as: next, id: "row:orchestration.job_definition:{next_job_id}"}
 *     edges:
 *       - {from: "job:{job.service}:{job.name}", kind: after, to: "job:{next.service}:{next.name}"}
 * </pre>
 */
public final class FactMapping {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");

    record Rule(String name, String kind, String idPrefix, Map<String, String> where, Each each, List<Lookup> lookups,
                List<NodeTemplate> nodes, List<EdgeTemplate> edges) {
    }

    /** #212: one pass per element of a JSON array in an attribute ({@code path} dotted inside it). */
    record Each(String attr, String path, String as) {
    }

    /** By {@code id}, or (#212) by {@code idSuffix} among the nodes of {@code kind}. */
    record Lookup(String as, String id, String kind, String idSuffix) {
    }

    record NodeTemplate(String id, String kind, Map<String, String> attrs) {
    }

    record EdgeTemplate(String from, String kind, String to, Map<String, String> attrs) {
    }

    /** What a run did: facts written per rule, templates skipped for a missing value (with an example). */
    public record Stats(Map<String, Integer> written, Map<String, Integer> skipped, Map<String, String> skippedExample) {
    }

    private final List<Rule> rules;

    private FactMapping(List<Rule> rules) {
        this.rules = rules;
    }

    public static FactMapping load(Path file) throws IOException {
        return parse(new ObjectMapper(new YAMLFactory()).readTree(file.toFile()));
    }

    static FactMapping parse(JsonNode root) {
        List<Rule> rules = new ArrayList<>();
        int i = 0;
        for (JsonNode r : root.path("mappings")) {
            i++;
            String name = r.path("name").asText("rule " + i);
            JsonNode m = r.path("match");
            if (m.path("kind").asText("").isBlank() && m.path("idPrefix").asText("").isBlank()) {
                throw new IllegalArgumentException("mapping '" + name + "': match needs a kind or an idPrefix");
            }
            List<Lookup> lookups = new ArrayList<>();
            for (JsonNode l : r.path("lookup")) {
                String id = l.path("id").asText(null);
                String suffix = l.path("idSuffix").asText(null);
                if ((id == null) == (suffix == null)) {
                    throw new IllegalArgumentException("mapping '" + name + "': a lookup needs an id or an idSuffix");
                }
                lookups.add(new Lookup(required(l, "as", name), id, l.path("kind").asText(null), suffix));
            }
            JsonNode e0 = r.path("each");
            Each each = e0.isMissingNode() ? null : new Each(required(e0, "attr", name), e0.path("path").asText(""),
                    e0.path("as").asText("item"));
            List<NodeTemplate> nodes = new ArrayList<>();
            r.path("nodes").forEach(n -> nodes.add(new NodeTemplate(required(n, "id", name), required(n, "kind", name), strings(n.path("attrs")))));
            List<EdgeTemplate> edges = new ArrayList<>();
            r.path("edges").forEach(e -> edges.add(new EdgeTemplate(required(e, "from", name), required(e, "kind", name),
                    required(e, "to", name), strings(e.path("attrs")))));
            if (nodes.isEmpty() && edges.isEmpty()) {
                throw new IllegalArgumentException("mapping '" + name + "' writes nothing: give it nodes or edges");
            }
            rules.add(new Rule(name, m.path("kind").asText(null), m.path("idPrefix").asText(null), strings(m.path("where")),
                    each, lookups, nodes, edges));
        }
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("no mappings: the file needs a 'mappings:' list");
        }
        return new FactMapping(rules);
    }

    /** Applies every rule to the knowledge base's nodes; the new facts go to {@code out}. */
    public Stats apply(KnowledgeBase kb, FactSink out) {
        Map<String, Integer> written = new TreeMap<>();
        Map<String, Integer> skipped = new TreeMap<>();
        Map<String, String> example = new TreeMap<>();
        Map<String, List<KnowledgeBase.Fact<Node>>> byId = new HashMap<>();
        kb.nodes().forEach(n -> byId.computeIfAbsent(n.fact().id(), k -> new ArrayList<>()).add(n));
        for (Rule rule : rules) {
            for (KnowledgeBase.Fact<Node> f : kb.nodes()) {
                Node n = f.fact();
                if (rule.kind() != null && !rule.kind().equals(n.kind())
                        || rule.idPrefix() != null && !n.id().startsWith(rule.idPrefix())
                        || !rule.where().entrySet().stream().allMatch(w -> Objects.equals(w.getValue(), str(n.attrs().get(w.getKey()))))) {
                    continue;
                }
                Evidence ev = evidence(n.evidence(), f.header());
                for (Map<String, String> element : elements(rule.each(), n)) {
                    for (Binding b : bind(rule.lookups(), new Binding(element, n.env()), byId, kb)) {
                        Map<String, String> values = b.values();
                        for (NodeTemplate t : rule.nodes()) {
                            String id = fill(t.id(), values);
                            Map<String, Object> attrs = attrs(t.attrs(), values, rule.name());
                            if (id == null || attrs == null || FactFormat.kindOf(id) == null || !FactFormat.kindOf(id).equals(t.kind())) {
                                skip(skipped, example, rule.name(), t.id(), n.id(), values);
                                continue;
                            }
                            out.node(new Node(id, t.kind(), b.env(), attrs, ev));
                            written.merge(rule.name(), 1, Integer::sum);
                        }
                        for (EdgeTemplate t : rule.edges()) {
                            String from = fill(t.from(), values);
                            String to = fill(t.to(), values);
                            Map<String, Object> attrs = attrs(t.attrs(), values, rule.name());
                            if (from == null || to == null || attrs == null || FactFormat.kindOf(from) == null || FactFormat.kindOf(to) == null) {
                                skip(skipped, example, rule.name(), t.from() + " -" + t.kind() + "-> " + t.to(), n.id(), values);
                                continue;
                            }
                            out.edge(Edge.extracted(from, t.kind(), to, attrs, ev).inEnv(b.env()));
                            written.merge(rule.name(), 1, Integer::sum);
                        }
                    }
                }
            }
        }
        return new Stats(written, skipped, example);
    }

    private record Binding(Map<String, String> values, String env) {
    }

    /** #212: the node's values, once per element of its {@code each} array ({@code <as>.*}, {@code prev.*}). */
    private static List<Map<String, String>> elements(Each each, Node n) {
        Map<String, String> base = values(n);
        if (each == null) {
            return List.of(base);
        }
        List<Map<String, String>> out = new ArrayList<>();
        Object raw = n.attrs().get(each.attr());
        JsonNode arr;
        try {
            arr = raw instanceof String str ? JSON.readTree(str) : JSON.valueToTree(raw);
        } catch (IOException | IllegalArgumentException e) {
            return out;
        }
        for (String part : each.path().isBlank() ? new String[0] : each.path().split("\\.")) {
            arr = arr == null ? null : arr.path(part);
        }
        if (arr == null || !arr.isArray()) {
            return out;
        }
        Map<String, String> prev = null;
        int i = 0;
        for (JsonNode el : arr) {
            i++;
            Map<String, String> fields = new LinkedHashMap<>();
            if (el.isObject()) {
                el.fields().forEachRemaining(e -> {
                    if (e.getValue().isValueNode() && !e.getValue().isNull()) {
                        fields.put(e.getKey(), e.getValue().asText());
                    }
                });
            } else if (el.isValueNode()) {
                fields.put("value", el.asText());
            }
            Map<String, String> v = new LinkedHashMap<>(base);
            fields.forEach((k, x) -> v.put(each.as() + "." + k, x));
            v.put(each.as() + "._index", String.valueOf(i));
            v.put(each.as() + "._count", String.valueOf(arr.size()));
            if (prev == null) {
                v.put("_first", "true");
            } else {
                prev.forEach((k, x) -> v.put("prev." + k, x));
            }
            out.add(v);
            prev = fields;
        }
        return out;
    }

    /**
     * The values after each lookup: a looked-up node's as {@code <as>.*}. A fact without environment matches nodes of
     * any (#212), and continues once per environment found; one with an environment, that one's or none's.
     */
    private static List<Binding> bind(List<Lookup> lookups, Binding start, Map<String, List<KnowledgeBase.Fact<Node>>> byId, KnowledgeBase kb) {
        List<Binding> bs = List.of(start);
        for (Lookup l : lookups) {
            List<Binding> next = new ArrayList<>();
            for (Binding b : bs) {
                String key = fill(l.id() != null ? l.id() : l.idSuffix(), b.values());
                List<KnowledgeBase.Fact<Node>> found = key == null ? List.of() : l.id() != null ? byId.getOrDefault(key, List.of())
                        : kb.nodes().stream().filter(x -> (l.kind() == null || l.kind().equals(x.fact().kind())) && x.fact().id().endsWith(key)).toList();
                Map<String, List<KnowledgeBase.Fact<Node>>> byEnv = new LinkedHashMap<>();
                for (KnowledgeBase.Fact<Node> x : found) {
                    if (b.env() == null || x.fact().env() == null || Objects.equals(b.env(), x.fact().env())) {
                        byEnv.computeIfAbsent(String.valueOf(x.fact().env()), k -> new ArrayList<>()).add(x);
                    }
                }
                if (key == null && b.values().containsKey("_first") && (l.id() != null ? l.id() : l.idSuffix()).contains("{prev.")) {
                    Map<String, String> v = new LinkedHashMap<>(b.values());
                    v.put("_noprev." + l.as(), "true");   // the first element: what it would name does not exist
                    next.add(new Binding(v, b.env()));
                    continue;
                }
                if (byEnv.isEmpty()) {
                    next.add(b);
                    continue;
                }
                for (List<KnowledgeBase.Fact<Node>> xs : byEnv.values()) {
                    Node x = xs.getFirst().fact();
                    Map<String, String> v = new LinkedHashMap<>(b.values());
                    values(x).forEach((k, y) -> v.put(l.as() + "." + k, y));
                    next.add(new Binding(v, b.env() != null ? b.env() : x.env()));
                }
            }
            bs = next;
        }
        return bs;
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static void skip(Map<String, Integer> skipped, Map<String, String> example, String rule, String template, String from,
                             Map<String, String> values) {
        if (values.containsKey("_first") && (template.contains("{prev.") || values.keySet().stream()
                .anyMatch(k -> k.startsWith("_noprev.") && template.contains("{" + k.substring("_noprev.".length()) + ".")))) {
            return;   // #212: the first element has none before it
        }
        skipped.merge(rule, 1, Integer::sum);
        example.putIfAbsent(rule, template + " for " + from);
    }

    /** The node's attributes as text, plus {@code _id}, {@code _env}, {@code _kind}. */
    private static Map<String, String> values(Node n) {
        Map<String, String> v = new LinkedHashMap<>();
        n.attrs().forEach((k, x) -> {
            String s = str(x);
            if (s != null) {
                v.put(k, s);
            }
        });
        v.put("_id", n.id());
        v.put("_kind", n.kind());
        if (n.env() != null) {
            v.put("_env", n.env());
        }
        return v;
    }

    private static Map<String, Object> attrs(Map<String, String> templates, Map<String, String> values, String rule) {
        Map<String, Object> out = new TreeMap<>();
        for (var t : templates.entrySet()) {
            String v = fill(t.getValue(), values);
            if (v == null) {
                return null;
            }
            out.put(t.getKey(), v);
        }
        out.put("mappedBy", rule);
        return out;
    }

    /** The template with every {@code {name}} replaced; null when a name has no (non-blank) value. */
    static String fill(String template, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(1).strip());
            if (v == null || v.isBlank()) {
                return null;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static Evidence evidence(Evidence e, Header h) {
        if (e == null) {
            return null;
        }
        return new Evidence(e.repo() != null ? e.repo() : h == null ? null : h.repo(), e.root(), e.file(), e.line(),
                e.commit() != null ? e.commit() : h == null ? null : h.commit());
    }

    private static String str(Object o) {
        return o == null ? null : o instanceof String s ? s : String.valueOf(o);
    }

    private static Map<String, String> strings(JsonNode n) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
            var e = it.next();
            out.put(e.getKey(), e.getValue().asText());
        }
        return out;
    }

    private static String required(JsonNode n, String field, String rule) {
        String v = n.path(field).asText("");
        if (v.isBlank()) {
            throw new IllegalArgumentException("mapping '" + rule + "': '" + field + "' is required");
        }
        return v;
    }
}
