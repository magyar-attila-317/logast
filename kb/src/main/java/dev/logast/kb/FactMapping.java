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

    record Rule(String name, String kind, String idPrefix, Map<String, Cond> where, Each each, List<Lookup> lookups,
                List<NodeTemplate> nodes, List<EdgeTemplate> edges) {
    }

    /** #212: one pass per element of a JSON array in an attribute ({@code path} dotted inside it). */
    record Each(String attr, String path, String as) {
    }

    /** By {@code id}, or (#212) by {@code idSuffix} among the nodes of {@code kind}; (#213) those meeting {@code where}. */
    record Lookup(String as, String id, String kind, String idSuffix, Map<String, Cond> where, boolean ignoreCase) {
    }

    /**
     * #213: a condition on a value: equal to a scalar, or {@code {not: x}}, {@code {in: [a, b]}}, {@code {regex: p}}
     * (found anywhere in it), {@code {contains: s}}, {@code {exists: true|false}}; several keys must all hold.
     */
    record Cond(String eq, String not, List<String> in, Pattern regex, String contains, Boolean exists) {
        boolean test(String v) {
            return (exists == null || exists == (v != null))
                    && (eq == null || eq.equals(v))
                    && (not == null || !not.equals(v))
                    && (in == null || v != null && in.contains(v))
                    && (regex == null || v != null && regex.matcher(v).find())
                    && (contains == null || v != null && v.contains(contains));
        }

        @Override
        public String toString() {
            List<String> parts = new ArrayList<>();
            if (eq != null) {
                parts.add("= " + eq);
            }
            if (not != null) {
                parts.add("not " + not);
            }
            if (in != null) {
                parts.add("in " + in);
            }
            if (regex != null) {
                parts.add("regex " + regex.pattern());
            }
            if (contains != null) {
                parts.add("contains " + contains);
            }
            if (exists != null) {
                parts.add(exists ? "exists" : "does not exist");
            }
            return String.join(", ", parts);
        }
    }

    static Map<String, Cond> where(JsonNode n, String rule) {
        Map<String, Cond> out = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
            var e = it.next();
            JsonNode c = e.getValue();
            if (!c.isObject()) {
                out.put(e.getKey(), new Cond(c.asText(), null, null, null, null, null));
                continue;
            }
            for (Iterator<String> ks = c.fieldNames(); ks.hasNext(); ) {
                String k = ks.next();
                if (!List.of("not", "in", "regex", "contains", "exists").contains(k)) {
                    throw new IllegalArgumentException("mapping '" + rule + "': where " + e.getKey() + ": unknown condition '" + k
                            + "' (not, in, regex, contains, exists)");
                }
            }
            List<String> in = null;
            if (c.has("in")) {
                in = new ArrayList<>();
                for (JsonNode x : c.path("in")) {
                    in.add(x.asText());
                }
            }
            out.put(e.getKey(), new Cond(null, c.has("not") ? c.path("not").asText() : null, in,
                    c.has("regex") ? Pattern.compile(c.path("regex").asText()) : null,
                    c.has("contains") ? c.path("contains").asText() : null,
                    c.has("exists") ? c.path("exists").asBoolean() : null));
        }
        return out;
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
                lookups.add(new Lookup(required(l, "as", name), id, l.path("kind").asText(null), suffix, where(l.path("where"), name),
                        l.path("ignoreCase").asBoolean(false)));
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
            Rule rule = new Rule(name, m.path("kind").asText(null), m.path("idPrefix").asText(null), where(m.path("where"), name),
                    each, lookups, nodes, edges);
            checkFilters(rule);
            rules.add(rule);
        }
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("no mappings: the file needs a 'mappings:' list");
        }
        return new FactMapping(rules);
    }

    /** Applies every rule to the knowledge base's nodes; the new facts go to {@code out}. */
    public Stats apply(KnowledgeBase kb, FactSink out) {
        return apply(kb, out, null, null);
    }

    /**
     * #213: as {@link #apply(KnowledgeBase, FactSink)}, and for the node {@code explain} what each rule did with it into
     * {@code trace}: whether it matched (and which condition failed), its {@code each} elements, each lookup's node,
     * each template filled or the placeholder that had no value.
     */
    public Stats apply(KnowledgeBase kb, FactSink out, String explain, List<String> trace) {
        if (explain != null && kb.nodes().stream().noneMatch(n -> n.fact().id().equals(explain))) {
            trace.add("no node " + explain + " in the facts");
        }
        Map<String, Integer> written = new TreeMap<>();
        Map<String, Integer> skipped = new TreeMap<>();
        Map<String, String> example = new TreeMap<>();
        Map<String, List<KnowledgeBase.Fact<Node>>> byId = new HashMap<>();
        kb.nodes().forEach(n -> byId.computeIfAbsent(n.fact().id(), k -> new ArrayList<>()).add(n));
        for (Rule rule : rules) {
            for (KnowledgeBase.Fact<Node> f : kb.nodes()) {
                Node n = f.fact();
                List<String> tr = explain != null && n.id().equals(explain) ? trace : null;
                String miss = mismatch(rule, n);
                if (miss != null) {
                    if (tr != null) {
                        tr.add("rule " + rule.name() + ": not matched: " + miss);
                    }
                    continue;
                }
                if (tr != null) {
                    tr.add("rule " + rule.name() + ": matched");
                }
                Evidence ev = evidence(n.evidence(), f.header());
                List<Map<String, String>> elements = elements(rule.each(), n);
                if (tr != null && rule.each() != null) {
                    tr.add("  each " + rule.each().attr() + (rule.each().path().isBlank() ? "" : "." + rule.each().path()) + ": "
                            + elements.size() + " element(s)" + (elements.isEmpty() ? " (not a JSON list there?)" : ""));
                }
                for (Map<String, String> element : elements) {
                    if (tr != null && rule.each() != null) {
                        tr.add("  element " + element.get(rule.each().as() + "._index") + ": " + prefixed(element, rule.each().as() + "."));
                    }
                    for (Binding b : bind(rule.lookups(), new Binding(element, n.env()), byId, kb, tr)) {
                        Map<String, String> values = b.values();
                        for (NodeTemplate t : rule.nodes()) {
                            String id = fill(t.id(), values);
                            Map<String, Object> attrs = attrs(t.attrs(), values, rule.name());
                            if (id == null || attrs == null || FactFormat.kindOf(id) == null || !FactFormat.kindOf(id).equals(t.kind())) {
                                skip(skipped, example, rule.name(), t.id(), n.id(), values, tr, t.attrs().values());
                                continue;
                            }
                            if (tr != null) {
                                tr.add("    node " + id + (b.env() == null ? "" : " [" + b.env() + "]"));
                            }
                            out.node(new Node(id, t.kind(), b.env(), attrs, ev));
                            written.merge(rule.name(), 1, Integer::sum);
                        }
                        for (EdgeTemplate t : rule.edges()) {
                            String from = fill(t.from(), values);
                            String to = fill(t.to(), values);
                            Map<String, Object> attrs = attrs(t.attrs(), values, rule.name());
                            if (from == null || to == null || attrs == null || FactFormat.kindOf(from) == null || FactFormat.kindOf(to) == null) {
                                skip(skipped, example, rule.name(), t.from() + " -" + t.kind() + "-> " + t.to(), n.id(), values, tr, t.attrs().values());
                                continue;
                            }
                            if (tr != null) {
                                tr.add("    edge " + from + " -" + t.kind() + "-> " + to + (b.env() == null ? "" : " [" + b.env() + "]"));
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
    private static List<Binding> bind(List<Lookup> lookups, Binding start, Map<String, List<KnowledgeBase.Fact<Node>>> byId, KnowledgeBase kb,
                                      List<String> tr) {
        List<Binding> bs = List.of(start);
        for (Lookup l : lookups) {
            List<Binding> next = new ArrayList<>();
            for (Binding b : bs) {
                String key = fill(l.id() != null ? l.id() : l.idSuffix(), b.values());
                List<KnowledgeBase.Fact<Node>> found = key == null ? List.of() : l.id() != null ? byId.getOrDefault(key, List.of())
                        : kb.nodes().stream().filter(x -> (l.kind() == null || l.kind().equals(x.fact().kind()))
                        && (l.ignoreCase() ? x.fact().id().toLowerCase(java.util.Locale.ROOT).endsWith(key.toLowerCase(java.util.Locale.ROOT))
                        : x.fact().id().endsWith(key))).toList();
                if (!l.where().isEmpty()) {   // #213
                    found = found.stream().filter(x -> mismatch(l.where(), values(x.fact())) == null).toList();
                }
                Map<String, List<KnowledgeBase.Fact<Node>>> byEnv = new LinkedHashMap<>();
                for (KnowledgeBase.Fact<Node> x : found) {
                    if (b.env() == null || x.fact().env() == null || Objects.equals(b.env(), x.fact().env())) {
                        byEnv.computeIfAbsent(String.valueOf(x.fact().env()), k -> new ArrayList<>()).add(x);
                    }
                }
                if (tr != null) {
                    String how = (l.id() != null ? "id " : "id ending ") + (key == null ? (l.id() != null ? l.id() : l.idSuffix())
                            + " (" + missing(l.id() != null ? l.id() : l.idSuffix(), b.values()) + ")" : key);
                    tr.add("    lookup " + l.as() + " by " + how + ": " + (byEnv.isEmpty() ? "nothing found"
                            : String.join(", ", byEnv.values().stream().map(xs -> xs.getFirst().fact().id()
                            + (xs.getFirst().fact().env() == null ? "" : " [" + xs.getFirst().fact().env() + "]")).toList())));
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
                             Map<String, String> values, List<String> tr, java.util.Collection<String> attrTemplates) {
        if (values.containsKey("_first") && (template.contains("{prev.") || values.keySet().stream()
                .anyMatch(k -> k.startsWith("_noprev.") && template.contains("{" + k.substring("_noprev.".length()) + ".")))) {
            if (tr != null) {
                tr.add("    " + template + ": none (the first element has none before it)");
            }
            return;   // #212: the first element has none before it
        }
        String why = missing(template, values);
        for (String a : attrTemplates) {
            why = why != null ? why : missing(a, values);
        }
        String reason = why != null ? why : "its id is not <kind>:<name> of that kind";
        if (tr != null) {
            tr.add("    " + template + ": skipped: " + reason);
        }
        skipped.merge(rule, 1, Integer::sum);
        example.putIfAbsent(rule, template + " for " + from + " (" + reason + ")");
    }

    /** #213: why a node does not match a rule (its kind, id prefix, a condition), null when it does. */
    private static String mismatch(Rule rule, Node n) {
        if (rule.kind() != null && !rule.kind().equals(n.kind())) {
            return "kind is " + n.kind() + ", not " + rule.kind();
        }
        if (rule.idPrefix() != null && !n.id().startsWith(rule.idPrefix())) {
            return "id does not start with " + rule.idPrefix();
        }
        return mismatch(rule.where(), values(n));
    }

    private static String mismatch(Map<String, Cond> where, Map<String, String> values) {
        for (var w : where.entrySet()) {
            String v = values.get(w.getKey());
            if (!w.getValue().test(v)) {
                return "where " + w.getKey() + " (" + w.getValue() + "): " + (v == null ? "not set" : "is " + v);
            }
        }
        return null;
    }

    /** #213: the first placeholder of {@code template} without a value, and the values there are; null when all have one. */
    static String missing(String template, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            String v = resolve(m.group(1), values);
            if (v == null || v.isBlank()) {
                String name = parts(m.group(1)).getFirst().strip();
                String scope = name.contains(".") ? name.substring(0, name.lastIndexOf('.') + 1) : "";
                List<String> have = values.keySet().stream().filter(k -> !k.startsWith("_noprev.") && !k.equals("_first")
                        && (scope.isEmpty() ? !k.contains(".") : k.startsWith(scope))).sorted().toList();
                return "{" + m.group(1).strip() + "} has no value" + (values.containsKey(name) ? " after its filters" : "")
                        + "; there are: " + String.join(", ", have);
            }
        }
        return null;
    }

    private static String prefixed(Map<String, String> values, String prefix) {
        List<String> out = new ArrayList<>();
        values.forEach((k, v) -> {
            if (k.startsWith(prefix) && !k.startsWith(prefix + "_")) {
                out.add(k.substring(prefix.length()) + "=" + v);
            }
        });
        return String.join(", ", out);
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

    /** The template with every {@code {name | filter…}} replaced; null when one has no (non-blank) value. */
    static String fill(String template, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = resolve(m.group(1), values);
            if (v == null || v.isBlank()) {
                return null;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static final List<String> FILTERS = List.of("lower", "upper", "trim", "default", "replace", "regex", "before", "after");

    /**
     * #213: a placeholder's value: {@code name}, then each {@code | filter}: {@code lower}, {@code upper}, {@code trim},
     * {@code default:'x'} (when it has none), {@code replace:'a':'b'}, {@code regex:'p'} (the first group, else the match;
     * none when it does not match), {@code before:'s'} (before the first s), {@code after:'s'} (after the last s).
     */
    static String resolve(String expr, Map<String, String> values) {
        List<String> ps = parts(expr);
        String v = values.get(ps.getFirst().strip());
        for (String f : ps.subList(1, ps.size())) {
            List<String> fa = filterArgs(f.strip());
            String name = fa.getFirst();
            String a1 = fa.size() > 1 ? fa.get(1) : "";
            String a2 = fa.size() > 2 ? fa.get(2) : "";
            if (name.equals("default")) {
                v = v == null || v.isBlank() ? a1 : v;
                continue;
            }
            if (v == null) {
                continue;   // nothing to filter: a later default can still give one
            }
            switch (name) {
                case "lower" -> v = v.toLowerCase(java.util.Locale.ROOT);
                case "upper" -> v = v.toUpperCase(java.util.Locale.ROOT);
                case "trim" -> v = v.strip();
                case "replace" -> v = v.replace(a1, a2);
                case "regex" -> {
                    Matcher rm = Pattern.compile(a1).matcher(v);
                    v = !rm.find() ? null : rm.groupCount() > 0 ? rm.group(1) : rm.group();
                }
                case "before" -> v = v.contains(a1) ? v.substring(0, v.indexOf(a1)) : v;
                case "after" -> v = v.contains(a1) ? v.substring(v.lastIndexOf(a1) + a1.length()) : v;
                default -> throw new IllegalArgumentException("unknown filter '" + name + "'");
            }
        }
        return v;
    }

    /** {@code a | lower | regex:'x|y'} → its parts at the bars outside quotes. */
    static List<String> parts(String expr) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (char ch : expr.toCharArray()) {
            if (ch == '\'') {
                quoted = !quoted;
            }
            if (ch == '|' && !quoted) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }

    /** {@code replace:'a':'b'} → [replace, a, b]. */
    private static List<String> filterArgs(String f) {
        List<String> out = new ArrayList<>();
        int colon = f.indexOf(':');
        out.add((colon < 0 ? f : f.substring(0, colon)).strip());
        if (colon < 0) {
            return out;
        }
        Matcher q = Pattern.compile("'([^']*)'").matcher(f.substring(colon));
        while (q.find()) {
            out.add(q.group(1));
        }
        return out;
    }

    /** A rule's templates use only filters there are (said when the file is read, not halfway through a run). */
    private static void checkFilters(Rule rule) {
        List<String> templates = new ArrayList<>();
        rule.nodes().forEach(t -> {
            templates.add(t.id());
            templates.addAll(t.attrs().values());
        });
        rule.edges().forEach(t -> {
            templates.add(t.from());
            templates.add(t.to());
            templates.addAll(t.attrs().values());
        });
        rule.lookups().forEach(l -> templates.add(l.id() != null ? l.id() : l.idSuffix()));
        for (String t : templates) {
            Matcher m = PLACEHOLDER.matcher(t);
            while (m.find()) {
                List<String> ps = parts(m.group(1));
                for (String f : ps.subList(1, ps.size())) {
                    String name = filterArgs(f.strip()).getFirst();
                    if (!FILTERS.contains(name)) {
                        throw new IllegalArgumentException("mapping '" + rule.name() + "': unknown filter '" + name + "' in " + t
                                + " (" + String.join(", ", FILTERS) + ")");
                    }
                }
            }
        }
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
