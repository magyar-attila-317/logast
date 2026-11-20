package dev.logast.kb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * #168: writes what the team's LLM proposes (business flows, summaries) to {@code knowledge.proposed.yml}, a file
 * logast manages next to the people's {@code knowledge.yml}. Each proposal records who made it, when, its sources and
 * the evidence of the nodes it covers, so a change of those makes it stale.
 */
public final class Proposals {
    public static final String FILE = "knowledge.proposed.yml";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER).enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));
    private static final String HEADER = "# Written by logast mcp (propose_flow, propose_summary): what the team's LLM proposes.\n"
            + "# To approve an entry, move it to knowledge.yml (or use the review page). This file is rewritten on each proposal.\n";

    private Proposals() {
    }

    /** Where proposals go: next to the first knowledge.yml under the paths, else in the first folder. */
    public static Path file(Collection<Path> paths) {
        for (Path f : KnowledgeBase.factFiles(paths)) {
            if (f.getFileName().toString().startsWith("knowledge.y")) {
                return f.resolveSibling(FILE);
            }
        }
        for (Path p : paths) {
            return Files.isDirectory(p) ? p.resolve(FILE) : p.toAbsolutePath().getParent().resolve(FILE);
        }
        return null;
    }

    static String flow(Path file, KnowledgeBase kb, String name, String description, List<String> nodes, List<String> sources) throws IOException {
        String problem = check(kb, nodes, sources);
        if (problem != null) {
            return problem;
        }
        ObjectNode root = read(file);
        ObjectNode flows = root.has("flows") && root.get("flows").isObject() ? (ObjectNode) root.get("flows") : root.putObject("flows");
        ObjectNode f = flows.putObject(name);
        if (description != null && !description.isBlank()) {
            f.put("description", description);
        }
        ArrayNode ns = f.putArray("nodes");
        nodes.forEach(ns::add);
        sign(f, kb, nodes, sources);
        write(file, root);
        return "Proposed flow \"" + name + "\" (" + nodes.size() + " nodes) in " + file + ": a person approves it by moving it to knowledge.yml.";
    }

    static String summary(Path file, KnowledgeBase kb, String node, String text, List<String> sources) throws IOException {
        String problem = check(kb, List.of(node), sources);
        if (problem != null) {
            return problem;
        }
        ObjectNode root = read(file);
        ObjectNode ss = root.has("summaries") && root.get("summaries").isObject() ? (ObjectNode) root.get("summaries") : root.putObject("summaries");
        ObjectNode s = ss.putObject(node);
        s.put("text", text);
        sign(s, kb, List.of(node), sources);
        write(file, root);
        return "Proposed a summary of " + node + " in " + file + ".";
    }

    private static String check(KnowledgeBase kb, List<String> nodes, List<String> sources) {
        if (nodes.isEmpty()) {
            return "A proposal needs the node ids it is about.";
        }
        List<String> unknown = nodes.stream().filter(n -> kb.evidenceOf(n) == null).toList();
        if (!unknown.isEmpty()) {
            return "Not in the facts: " + String.join(", ", unknown) + ". Use ids from find, describe or lineage.";
        }
        if (sources.isEmpty()) {
            return "A proposal needs sources: the facts, glossary terms or pages it rests on.";
        }
        return null;
    }

    private static void sign(ObjectNode o, KnowledgeBase kb, List<String> nodes, List<String> sources) {
        ArrayNode src = o.putArray("sources");
        sources.forEach(src::add);
        o.put("by", "llm");
        o.put("at", Instant.now().toString());
        ObjectNode ev = o.putObject("evidence");
        nodes.forEach(n -> ev.put(n, kb.evidenceOf(n)));
    }

    private static ObjectNode read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return YAML.createObjectNode();
        }
        JsonNode n = YAML.readTree(file.toFile());
        return n != null && n.isObject() ? (ObjectNode) n : YAML.createObjectNode();
    }

    private static void write(Path file, ObjectNode root) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, HEADER + YAML.writeValueAsString(root));
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
