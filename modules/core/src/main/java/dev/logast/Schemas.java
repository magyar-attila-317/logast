package dev.logast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * #321: JSON schemas for the YAML files logast writes and reads, so an IDE completes and checks them: logast.yaml (also
 * company.yaml, a policy layer), the decision files and the vocabulary worksheet. Each file starts with the two
 * modelines IDEs read, pointing at the schema copied next to the files ({@code .logast/schemas/}, kept in git):
 * {@code # $schema: <path>} (IntelliJ) and {@code # yaml-language-server: $schema=<path>} (VS Code's YAML extension).
 * The schema files are logast's resources; the rule ids and pack names are filled in from the code when they are
 * written, so they never drift.
 */
public final class Schemas {
    public static final String DIR = LogastDir.DIR + "/schemas";
    public static final String POLICY = "logast.schema.json";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INTELLIJ = "# $schema: ";
    private static final String VSCODE = "# yaml-language-server: $schema=";

    public static final String CONFIG = "config.schema.json";
    public static final String MAPPING = "mapping.schema.json";
    /** The kb module's (dev.logast.kb.KbSchemas stamps its files): present where the kb is, as in the CLI. */
    private static final String KNOWLEDGE = "knowledge.schema.json";

    /** File name → its schema (the user config and fact mappings have names of their own: {@link #CONFIG}, {@link #MAPPING}). */
    static final Map<String, String> FOR = Map.ofEntries(
            Map.entry("logast.yaml", POLICY), Map.entry("logast.yml", POLICY), Map.entry("company.yaml", POLICY),
            Map.entry("messages.yaml", "messages.schema.json"), Map.entry("metrics.yaml", "metrics.schema.json"),
            Map.entry("retry.yaml", "retry.schema.json"), Map.entry("traffic.yaml", "traffic.schema.json"),
            Map.entry(CentralVocabulary.WORKSHEET, "vocabulary-worksheet.schema.json"),
            Map.entry("repos.yaml", "repos.schema.json"));

    /** Every schema, with the files it is for: what {@code logast schemas} lists. */
    public static final Map<String, String> FILES = new java.util.LinkedHashMap<>();

    static {
        FILES.put(POLICY, "logast.yaml, logast.yml, a fleet's company.yaml");
        FILES.put("messages.schema.json", ".logast/decisions/messages.yaml");
        FILES.put("metrics.schema.json", ".logast/decisions/metrics.yaml");
        FILES.put("retry.schema.json", ".logast/decisions/retry.yaml");
        FILES.put("traffic.schema.json", ".logast/decisions/traffic.yaml");
        FILES.put("vocabulary-worksheet.schema.json", "a fleet's vocabulary-worksheet.yaml");
        FILES.put("repos.schema.json", "a fleet repo's repos.yaml");
        FILES.put(KNOWLEDGE, "knowledge.yml, knowledge.proposed.yml, knowledge.decisions.yml");
        FILES.put(MAPPING, "a fact mapping (logast facts --mapping, kb mappings)");
        FILES.put(CONFIG, "~/.config/logast/config.yaml");
    }

    private Schemas() {
    }

    /** Every schema logast has, by file name. */
    public static List<String> names() {
        return FILES.keySet().stream().sorted().toList();
    }

    /** The schema's text, with the rule ids and packs of this logast filled in for the policy schema. */
    public static String text(String name) {
        try (InputStream in = Schemas.class.getResourceAsStream("/schemas/" + name)) {
            String raw = new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
            if (!name.equals(POLICY)) {
                return raw;
            }
            ObjectNode root = (ObjectNode) JSON.readTree(raw);
            ObjectNode props = (ObjectNode) root.get("properties");
            ArrayNode packs = ((ObjectNode) props.get("packs").get("items")).putArray("enum");
            dev.logast.rules.Rules.packs().forEach(packs::add);
            ArrayNode rules = ((ObjectNode) props.get("rules")).putObject("propertyNames").putArray("enum");
            dev.logast.rules.Rules.all().forEach(rules::add);
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Where the schemas of {@code file} live: the {@code .logast/schemas/} of its project for a service's files
     * (logast.yaml at its root, the decision files under .logast/), else a {@code schemas/} folder beside it (a fleet's
     * company.yaml and vocabulary worksheet).
     */
    static Path dirFor(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        for (Path p = abs.getParent(); p != null; p = p.getParent()) {
            if (p.getFileName() != null && p.getFileName().toString().equals(LogastDir.DIR)) {
                return p.resolve("schemas");
            }
        }
        String name = abs.getFileName().toString();
        return abs.getParent().resolve(name.startsWith("logast.") ? DIR : "schemas");
    }

    /**
     * {@code text} as {@code file} should hold it: the two modelines first (replacing any earlier ones), its schema
     * written to the schema folder when it is not there or differs. A file logast has no schema for is returned as is.
     */
    public static String stamp(Path file, String text) {
        String schema = FOR.get(file.getFileName().toString());
        return schema == null ? text : stamp(file, text, schema);
    }

    /** {@link #stamp(Path, String)} with the schema named: for files whose name says nothing (the user config). */
    public static String stamp(Path file, String text, String schema) {
        Path dir = dirFor(file);
        write(dir, schema);
        String rel = file.toAbsolutePath().normalize().getParent().relativize(dir.resolve(schema)).toString().replace('\\', '/');
        String lines = modelines(rel);
        // #54: a file with CRLF line endings keeps them, the two lines too
        return (text.contains("\r\n") ? lines.replace("\n", "\r\n") : lines) + withoutModelines(text);
    }

    /** The two modelines for a schema at {@code rel} (relative to the YAML file). */
    public static String modelines(String rel) {
        return INTELLIJ + rel + "\n" + VSCODE + rel + "\n";
    }

    public static String withoutModelines(String text) {
        String out = text;
        while (out.startsWith(INTELLIJ) || out.startsWith(VSCODE)) {   // a CRLF line goes with its \r
            int nl = out.indexOf('\n');
            out = nl < 0 ? "" : out.substring(nl + 1);
        }
        return out;
    }

    /** Writes every schema into {@code dir} (unchanged ones are left alone). */
    public static void writeAll(Path dir) {
        names().forEach(n -> write(dir, n));
    }

    /** The schema for a file of this name (logast.yaml, messages.yaml, …), or null. */
    public static String forFile(String fileName) {
        return FOR.get(fileName);
    }

    /** Whether this logast carries the schema (the kb's come with the kb module). */
    public static boolean available(String name) {
        return Schemas.class.getResource("/schemas/" + name) != null;
    }

    private static void write(Path dir, String name) {
        if (!available(name)) {
            return;
        }
        try {
            Path f = dir.resolve(name);
            String text = text(name);
            if (Files.isRegularFile(f) && Files.readString(f, StandardCharsets.UTF_8).equals(text)) {
                return;
            }
            Files.createDirectories(dir);
            Files.writeString(f, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * #321: every setting of the policy schema with its built-in default (or an example where it has none), as
     * commented YAML: the end of a new logast.yaml, so what can be set and what it is now is in the file.
     */
    public static String reference() {
        try {
            JsonNode root = JSON.readTree(text(POLICY));
            StringBuilder sb = new StringBuilder();
            sb.append("# ---------------------------------------------------------------------------------------------\n")
                    .append("# Every setting with its built-in default; [example] marks a value that only shows the form.\n")
                    .append("# Copy a line above the dashes and remove the # to change it.\n")
                    .append("# The IDE completes and checks this file against ").append(DIR).append('/').append(POLICY).append(".\n")
                    .append("# ---------------------------------------------------------------------------------------------\n");
            Iterator<Map.Entry<String, JsonNode>> it = root.get("properties").fields();
            while (it.hasNext()) {
                var e = it.next();
                if (!List.of("extends", "fleet", "rollout").contains(e.getKey())) {   // set by fleet commands, not chosen here
                    reference(sb, e.getKey(), e.getValue(), 0);
                }
            }
            return sb.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void reference(StringBuilder sb, String key, JsonNode p, int depth) throws IOException {
        if (key.equals("override") || p.path("description").asText("").startsWith("Earlier")) {
            return;   // merge switches and earlier forms are not settings to choose
        }
        String indent = "  ".repeat(depth);
        String desc = p.path("description").asText("");
        JsonNode value = p.has("default") ? p.get("default") : p.path("examples").path(0);
        boolean nested = p.path("type").asText("").equals("object") && p.has("properties") && !p.has("default");
        if (nested) {
            sb.append("# ").append(indent).append(key).append(":").append(desc.isEmpty() ? "" : "   # " + shortText(desc)).append('\n');
            Iterator<Map.Entry<String, JsonNode>> it = p.get("properties").fields();
            while (it.hasNext()) {
                var e = it.next();
                reference(sb, e.getKey(), e.getValue(), depth + 1);
            }
            return;
        }
        String shown = value.isMissingNode() ? "" : " " + flow(value);
        // a default is what applies; an example only shows the form (marked, so it is not read as the default)
        String example = !p.has("default") && !value.isMissingNode() ? " [example]" : "";
        String enums = p.has("enum") ? enumText(p.get("enum")) : p.path("items").has("enum") ? enumText(p.get("items").get("enum")) : "";
        sb.append("# ").append(indent).append(key).append(":").append(shown).append("   #").append(example).append(' ')
                .append(shortText(desc)).append(enums).append('\n');
    }

    private static String enumText(JsonNode values) {
        StringBuilder sb = new StringBuilder(" (");
        for (int i = 0; i < values.size(); i++) {
            sb.append(i == 0 ? "" : " | ").append(values.get(i).asText());
        }
        String s = sb.append(')').toString();
        return s.length() > 90 ? "" : s;
    }

    /** The first sentence (to ";" or ":" at most 110 characters). */
    private static String shortText(String desc) {
        String s = desc.replace('\n', ' ');
        return s.length() <= 110 ? s : s.substring(0, 107).replaceFirst("\\s+\\S*$", "") + " …";
    }

    /** A value as one line of YAML flow style. */
    private static String flow(JsonNode v) throws IOException {
        if (v.isTextual()) {
            String t = v.asText();
            return t.isEmpty() || t.matches(".*[:#{}\\[\\],&*!|>'\"%@`].*|^[-?].*|true|false|null|[0-9.]+") ? JSON.writeValueAsString(t) : t;
        }
        if (v.isArray()) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < v.size(); i++) {
                sb.append(i == 0 ? "" : ", ").append(flow(v.get(i)));
            }
            return sb.append(']').toString();
        }
        if (v.isObject()) {
            StringBuilder sb = new StringBuilder("{");
            Iterator<Map.Entry<String, JsonNode>> it = v.fields();
            boolean first = true;
            while (it.hasNext()) {
                var e = it.next();
                sb.append(first ? "" : ", ").append(e.getKey().matches("[\\w.-]+") ? e.getKey() : JSON.writeValueAsString(e.getKey()))
                        .append(": ").append(flow(e.getValue()));
                first = false;
            }
            return sb.append('}').toString();
        }
        return v.toString();
    }
}
