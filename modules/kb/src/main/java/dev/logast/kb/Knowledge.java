package dev.logast.kb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * #163: what a company knows that no file proves, written by people in {@code knowledge.yml} next to the facts:
 * a glossary (terms with a definition, a source and aliases), abbreviations, the other names a service goes by, and
 * decisions on inferred links. Applied to the facts mechanically, before any LLM reads them.
 *
 * <pre>
 * glossary:
 *   order:
 *     definition: A customer's request to buy, from checkout to delivery.
 *     source: https://wiki.example.com/glossary#order
 *     aliases: [purchase]
 * abbreviations:
 *   ord: order
 *   inv: invoice
 * services:
 *   orders: [order-service, ord-svc]
 * decisions:
 *   - link: "service:billing -deployed-as-> deployment:billing-worker"
 *     env: prod
 *     decision: reject
 *     by: jane
 *     evidence: "deploy/charts/billing/templates/deployment.yaml"
 * </pre>
 */
public final class Knowledge {
    public record Term(String term, String definition, String source, List<String> aliases) {
    }

    /**
     * #168: a business flow: the nodes it consists of, what it rests on; {@code proposed} when an LLM wrote it
     * (knowledge.proposed.yml) and no person moved it to knowledge.yml yet; {@code evidence} what its nodes' evidence was.
     */
    public record Flow(String name, String description, List<String> nodes, List<String> sources, boolean proposed, String by,
                       Map<String, String> evidence) {
    }

    /** #168: a summary of a node (a script, a job), with its sources; proposed like a flow. */
    public record Summary(String node, String text, List<String> sources, boolean proposed, String by, Map<String, String> evidence) {
    }

    /** A decision on an inferred link: approve or reject; {@code evidence} is what it was decided on. */
    public record Decision(String link, String env, boolean approve, String by, String evidence, Path file) {
    }

    private final Map<String, Term> terms = new LinkedHashMap<>();
    private final Map<String, String> abbreviations = new LinkedHashMap<>();
    private final Map<String, String> serviceNames = new LinkedHashMap<>();   // other name → service
    private final List<Decision> decisions = new ArrayList<>();
    private final Map<String, Flow> flows = new LinkedHashMap<>();
    private final Map<String, Summary> summaries = new LinkedHashMap<>();
    private final List<String> problems = new ArrayList<>();
    private final List<Path> files = new ArrayList<>();
    private final List<String> about = new ArrayList<>();   // what the team tells an assistant first (#244)

    /** #244: the {@code about:} of the knowledge files: what the team tells an assistant before its first question. */
    public List<String> about() {
        return List.copyOf(about);
    }

    static Knowledge empty() {
        return new Knowledge();
    }

    void load(Path file) {
        files.add(file);
        JsonNode root;
        try {
            root = new ObjectMapper(new YAMLFactory()).readTree(file.toFile());
        } catch (IOException e) {
            problems.add(file + ": " + e.getMessage());
            return;
        }
        if (root == null) {
            return;
        }
        if (root.path("about").isTextual() && !root.path("about").asText().isBlank()) {
            about.add(root.path("about").asText().strip());
        }
        root.path("glossary").fields().forEachRemaining(e -> {
            JsonNode t = e.getValue();
            List<String> aliases = new ArrayList<>();
            t.path("aliases").forEach(a -> aliases.add(a.asText()));
            String definition = t.isTextual() ? t.asText() : t.path("definition").asText(null);
            terms.put(norm(e.getKey()), new Term(e.getKey(), definition, t.path("source").asText(null), aliases));
        });
        root.path("abbreviations").fields().forEachRemaining(e -> abbreviations.put(norm(e.getKey()), e.getValue().asText()));
        root.path("services").fields().forEachRemaining(e -> e.getValue().forEach(n -> serviceNames.put(n.asText(), e.getKey())));
        boolean proposed = file.getFileName().toString().startsWith("knowledge.proposed");
        root.path("flows").fields().forEachRemaining(e -> {
            JsonNode f = e.getValue();
            if (!proposed || !flows.containsKey(e.getKey())) {   // an approved flow wins over a proposal of the same name
                flows.put(e.getKey(), new Flow(e.getKey(), f.path("description").asText(null), texts(f.path("nodes")),
                        texts(f.path("sources")), proposed, f.path("by").asText(proposed ? "llm" : null), strings(f.path("evidence"))));
            }
        });
        root.path("summaries").fields().forEachRemaining(e -> {
            JsonNode f = e.getValue();
            if (!proposed || !summaries.containsKey(e.getKey())) {
                summaries.put(e.getKey(), new Summary(e.getKey(), f.path("text").asText(null), texts(f.path("sources")), proposed,
                        f.path("by").asText(proposed ? "llm" : null), strings(f.path("evidence"))));
            }
        });
        for (JsonNode d : root.path("decisions")) {
            String link = d.path("link").asText("");
            String decision = d.path("decision").asText("");
            if (link.isBlank() || !(decision.equals("approve") || decision.equals("reject"))) {
                problems.add(file + ": a decision needs a link and decision: approve or reject (" + d + ")");
                continue;
            }
            decisions.add(new Decision(link.strip(), d.path("env").asText(null), decision.equals("approve"), d.path("by").asText(null),
                    d.path("evidence").asText(null), file));
        }
    }

    private static List<String> texts(JsonNode n) {
        List<String> out = new ArrayList<>();
        n.forEach(x -> out.add(x.asText()));
        return out;
    }

    private static Map<String, String> strings(JsonNode n) {
        Map<String, String> out = new LinkedHashMap<>();
        n.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        return out;
    }

    /**
     * #170: the glossary as a {@code vocabulary:} block for logast's company.yaml: each term with its aliases (the words
     * logs must not use). Terms and aliases {@code existing} (term → aliases) already has are left out.
     */
    public String vocabularyYaml(Map<String, java.util.Set<String>> existing) {
        StringBuilder sb = new StringBuilder("vocabulary:\n");
        int n = 0;
        for (Term t : terms.values()) {
            java.util.Set<String> have = existing.get(t.term());
            List<String> aliases = t.aliases().stream().filter(a -> have == null || !have.contains(a)).toList();
            if (have != null && aliases.isEmpty()) {
                continue;
            }
            n++;
            sb.append("  ").append(t.term()).append(":");
            if (t.definition() != null) {
                sb.append("   # ").append(t.definition().lines().findFirst().orElse(""));
            }
            sb.append('\n');
            if (!aliases.isEmpty()) {
                sb.append("    aliases: [").append(String.join(", ", aliases)).append("]\n");
            }
        }
        return n == 0 ? "# every glossary term is in the vocabulary already\n" : sb.toString();
    }

    /** Whether a word is a term, an alias or an abbreviation. */
    boolean knows(String word) {
        String w = norm(word);
        return terms.containsKey(w) || abbreviations.containsKey(w)
                || terms.values().stream().anyMatch(t -> t.aliases().stream().anyMatch(a -> norm(a).equals(w)));
    }

    public Map<String, Flow> flows() {
        return java.util.Collections.unmodifiableMap(flows);
    }

    public Map<String, Summary> summaries() {
        return java.util.Collections.unmodifiableMap(summaries);
    }

    public List<Path> files() {
        return List.copyOf(files);
    }

    public List<String> problems() {
        return List.copyOf(problems);
    }

    List<Decision> decisions() {
        return List.copyOf(decisions);
    }

    /** The service another name stands for, or null. */
    String serviceFor(String name) {
        return serviceNames.get(name);
    }

    /** A term, one of its aliases, or an abbreviation (whose expansion may be a term). */
    public String term(String name) {
        String n = norm(name);
        Term t = terms.get(n);
        String via = null;
        if (t == null) {
            for (Term x : terms.values()) {
                if (x.aliases().stream().anyMatch(a -> norm(a).equals(n))) {
                    t = x;
                    via = "an alias of " + x.term();
                    break;
                }
            }
        }
        if (t == null && abbreviations.containsKey(n)) {
            String full = abbreviations.get(n);
            Term x = terms.get(norm(full));
            if (x == null) {
                return name + ": abbreviation of \"" + full + "\" (not in the glossary)";
            }
            t = x;
            via = "abbreviation of " + x.term();
        }
        if (t == null) {
            return "\"" + name + "\" is not in the glossary, the aliases or the abbreviations" + (files.isEmpty() ? " (no knowledge.yml loaded)" : "") + ".";
        }
        return t.term() + (via == null ? "" : " (" + name + ": " + via + ")") + "\n" + (t.definition() == null ? "(no definition)" : t.definition())
                + (t.source() == null ? "" : "\nsource: " + t.source()) + (t.aliases().isEmpty() ? "" : "\naliases: " + String.join(", ", t.aliases()));
    }

    /**
     * A name as business words: split at {@code _ - . /} and camel case, each part through the abbreviations and the
     * glossary's aliases: {@code ord_inv_ref} → order invoice ref. Null when nothing changes.
     */
    public String read(String name) {
        List<String> words = new ArrayList<>();
        boolean changed = false;
        for (String token : tokens(name)) {
            String t = norm(token);
            String w = abbreviations.get(t);
            if (w == null) {
                for (Term x : terms.values()) {
                    if (x.aliases().stream().anyMatch(a -> norm(a).equals(t))) {
                        w = x.term();
                        break;
                    }
                }
            }
            if (w != null && !w.equalsIgnoreCase(token)) {
                changed = true;
            }
            words.add(w != null ? w : t);
        }
        return changed ? String.join(" ", words) : null;
    }

    static List<String> tokens(String name) {
        List<String> out = new ArrayList<>();
        for (String part : name.split("[_\\-./:\\s]+")) {
            for (String w : part.split("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")) {
                if (!w.isBlank()) {
                    out.add(w);
                }
            }
        }
        return out;
    }

    private static String norm(String s) {
        return s.strip().toLowerCase(Locale.ROOT);
    }
}
