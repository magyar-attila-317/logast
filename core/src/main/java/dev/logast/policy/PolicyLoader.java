package dev.logast.policy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * Builds a {@link Policy} from layers: {@code classpath:logast-defaults.yaml} → each {@code extends:} parent
 * (depth first, in order) → the file itself.
 * <ul>
 *   <li>objects merge deeply, lists append (distinct), scalars of later layers win;</li>
 *   <li>{@code override: true} on an object replaces it instead of merging;</li>
 *   <li>{@code packs}, {@code mdc.ambientKeys} and {@code metrics.defaults} are replaced, not appended (they are
 *       selections: {@code defaults: []} switches them all off);</li>
 *   <li>every explicit override, and every scalar a later layer changes after a non-default layer set it, is
 *       recorded in {@link Policy#overrides()} so the standard's deviations stay visible.</li>
 * </ul>
 */
public final class PolicyLoader {
    static final String DEFAULTS = "classpath:logast-defaults.yaml";
    private static final Set<String> REPLACED_LISTS = Set.of("packs", "mdc.ambientKeys", "metrics.defaults");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final List<String> overrides = new ArrayList<>();
    /** YAML path → the layer that last set it; list items appended by a later layer as {@code path[i]}. */
    private final Map<String, String> origin = new LinkedHashMap<>();
    private final Deque<String> loading = new ArrayDeque<>();
    /** #56: extends: URLs without a #sha256= pin, with the hash to pin them to. */
    private final List<String> unpinned = new ArrayList<>();
    /** A file's location → where its relative extends resolve instead (the project of apply's scratch copy). */
    private final Map<String, String> relativeTo = new HashMap<>();

    private PolicyLoader() {
    }

    /** {@code <root>/logast.yaml}, or {@code logast.yml} when only that exists. */
    public static Path defaultFile(Path root) {
        Path yaml = root.resolve("logast.yaml");
        Path yml = root.resolve("logast.yml");
        return !Files.exists(yaml) && Files.exists(yml) ? yml : yaml;
    }

    /** @param explicit policy file; null → {@code <root>/logast.yaml} (or .yml) when present, else defaults only */
    public static Policy load(Path root, Path explicit) {
        return load(root, explicit, List.of());
    }

    /**
     * #132: the policy of a repo a fleet added without touching it: logast's defaults → the fleet's company.yaml (when
     * it has one) → the repo's own logast.yaml, only if it already has one. The fleet is given, not read from the file.
     */
    public static Policy forFleet(Path root, Path fleetDir) {
        Path company = fleetDir.resolve(dev.logast.CentralVocabulary.COMPANY).toAbsolutePath().normalize();
        return load(root, null, Files.isRegularFile(company) ? List.of(company.toString()) : List.of());
    }

    /**
     * @param extraParents layered after the defaults as if the service's file extended them first: what a central
     *                     layer (company.yaml) would change before the service extends it (#113, fleet --vocabulary)
     */
    public static Policy load(Path root, Path explicit, List<String> extraParents) {
        return load(root, explicit, extraParents, null);
    }

    /**
     * @param project where the file's relative {@code extends:} resolve when {@code root} is a copy of the project
     *                (apply works in a scratch copy: {@code extends: [../central/company.yaml]} points outside it, #113);
     *                null: next to the file
     */
    public static Policy load(Path root, Path explicit, List<String> extraParents, Path project) {
        PolicyLoader loader = new PolicyLoader();
        ObjectNode merged = loader.merge(root, explicit, extraParents, project);
        Path file = explicit != null ? explicit : root == null ? null : defaultFile(root);
        // #127: no layer chose the support mode: dependency when the service's build resolves the starter
        SupportResolvable.decide(merged, project != null ? project : root);
        merged.remove("extends");
        merged.remove("fleet");
        merged.remove("rollout");   // #236: the repo's own rollout intent, not policy
        merged.set("overrides", JsonNodeFactory.instance.arrayNode().addAll(loader.overrides.stream().map(TextNode::new).toList()));
        merged.remove("warnings");
        Policy policy;
        try {
            policy = YAML.treeToValue(merged, Policy.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid policy (" + file + "): " + e.getMessage(), e);
        }
        return loader.validate(policy);
    }

    /** The layers merged (defaults, {@code extraParents}, the file with its parents), as the policy is read from. */
    private ObjectNode merge(Path root, Path explicit, List<String> extraParents, Path project) {
        ObjectNode merged = JsonNodeFactory.instance.objectNode();
        layer(merged, DEFAULTS, null);
        for (String extra : extraParents) {
            layer(merged, extra, null);
        }
        Path file = explicit != null ? explicit : root == null ? null : defaultFile(root);
        if (file != null && Files.exists(file)) {
            String top = file.toAbsolutePath().normalize().toString();
            if (project != null) {
                relativeTo.put(top, project.toAbsolutePath().normalize().resolve(file.getFileName()).toString());
            }
            layer(merged, top, null);
        } else if (explicit != null) {
            throw new IllegalArgumentException("policy file not found: " + explicit);
        }
        return merged;
    }

    /** The merged layers of the project's policy as written, before any default is decided (#127). */
    static ObjectNode merged(Path root, Path explicit) {
        return new PolicyLoader().merge(root, explicit, List.of(), null);
    }

    /** Errors fail the load, all of them in one message; warnings travel with the policy. */
    private Policy validate(Policy policy) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>(unpinned);
        for (PolicyValidator.Problem pr : PolicyValidator.check(policy, path -> !label(DEFAULTS).equals(source(path)))) {
            (pr.error() ? errors : warnings).add(source(pr.path()) + ": " + pr.path() + ": " + pr.message());
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("invalid policy:\n  " + String.join("\n  ", errors)
                    + (warnings.isEmpty() ? "" : "\nwarnings:\n  " + String.join("\n  ", warnings)));
        }
        return policy.withWarnings(warnings);
    }

    /**
     * A policy file's own {@code vocabulary} and the one its {@code extends:} parents (and the defaults) give it, as
     * written: which of the file's terms a parent now defines (#113).
     *
     * @param inheritedAreas the parents' {@code areas} (each with its {@code vocabulary}, #121)
     * @param parents        the parents' file names (or URLs)
     */
    public record VocabularyLayers(JsonNode own, JsonNode inherited, JsonNode inheritedAreas, List<String> parents) {
    }

    /**
     * The {@code vocabulary.*.mdc.sources.types} the service's own policy file names; null when the file extends no
     * other layer (then every type is its own).
     */
    public static Set<String> ownSourceTypes(Path root) {
        Path file = defaultFile(root);
        if (!Files.exists(file)) {
            return null;
        }
        JsonNode node;
        try {
            node = YAML.readTree(Files.readString(file));
        } catch (IOException e) {
            return null;
        }
        if (node == null || entries(node.path("extends")).isEmpty() && fleetCompany(node.path("fleet").asText("")) == null) {
            return null;
        }
        Set<String> out = new HashSet<>();
        node.path("vocabulary").forEach(t -> t.path("mdc").path("sources").path("types").fieldNames().forEachRemaining(out::add));
        return out;
    }

    /**
     * The YAML paths ({@code packs}, {@code logging.keyStyle}, …) that the file's parents set: its fleet's company.yaml
     * and its {@code extends:} layers, not the built-in defaults. The setup leaves these to the parents (#124).
     */
    public static Set<String> parentSettings(Path file) {
        PolicyLoader loader = new PolicyLoader();
        ObjectNode merged = JsonNodeFactory.instance.objectNode();
        loader.layer(merged, DEFAULTS, null);
        String resolved = file.toAbsolutePath().normalize().toString();
        JsonNode node = loader.read(resolved);
        if (node != null && node.isObject()) {
            for (String parent : loader.parents(node)) {
                loader.layer(merged, parent, resolved);
            }
        }
        Set<String> out = new TreeSet<>();
        loader.origin.forEach((path, layer) -> {
            if (!layer.equals(label(DEFAULTS))) {
                out.add(path);
            }
        });
        return out;
    }

    /** True when {@code settings} (from {@link #parentSettings}) sets {@code path}, a part of it, or an object around it. */
    public static boolean sets(Set<String> settings, String path) {
        return settings.stream().anyMatch(s -> s.equals(path) || s.startsWith(path + ".") || s.startsWith(path + "[")
                || path.startsWith(s + "."));
    }

    public static VocabularyLayers vocabularyLayers(Path file) {
        return vocabularyLayers(file, null);
    }

    /**
     * #133: as {@link #vocabularyLayers(Path)}, with {@code onlyParent} (a path) as the file's only parent: what one layer
     * (the fleet's company.yaml) defines, without the file's other {@code extends:}.
     */
    public static VocabularyLayers vocabularyLayers(Path file, String onlyParent) {
        PolicyLoader loader = new PolicyLoader();
        ObjectNode merged = JsonNodeFactory.instance.objectNode();
        loader.layer(merged, DEFAULTS, null);
        String resolved = file.toAbsolutePath().normalize().toString();
        JsonNode node = loader.read(resolved);
        List<String> parents = new ArrayList<>();
        if (node != null && node.isObject()) {
            // #124: a fleet member extends the fleet's company.yaml (#133: also as `extends: [fleet:<name>]`)
            for (String parent : onlyParent != null ? List.of(onlyParent) : loader.parents(node)) {
                loader.layer(merged, parent, resolved);
                parents.add(label(resolve(parent, resolved)));
            }
        }
        return new VocabularyLayers(node == null ? JsonNodeFactory.instance.objectNode() : node.path("vocabulary"),
                merged.path("vocabulary"), merged.path("areas"), parents);
    }

    /**
     * The layer that set {@code path}: the path itself, else the last layer that set something below it (a map key
     * whose entries merged), else the nearest enclosing path.
     */
    private String source(String path) {
        for (String p = path; !p.isEmpty(); p = parentPath(p)) {
            if (origin.containsKey(p)) {
                return origin.get(p);
            }
            String below = null;
            for (var e : origin.entrySet()) {
                if (e.getKey().startsWith(p + ".") || e.getKey().startsWith(p + "[")) {
                    below = e.getValue();
                }
            }
            if (below != null) {
                return below;
            }
        }
        return label(DEFAULTS);
    }

    private static String parentPath(String path) {
        int cut = Math.max(path.lastIndexOf('.'), path.lastIndexOf('['));
        return cut < 0 ? "" : path.substring(0, cut);
    }

    private void layer(ObjectNode target, String location, String from) {
        String resolved = resolve(location, from);
        if (loading.contains(resolved)) {
            throw new IllegalArgumentException("extends cycle: " + loading + " -> " + resolved);
        }
        loading.push(resolved);
        JsonNode node = read(resolved);
        if (node != null && node.isObject()) {
            // #124: the fleet's company.yaml, before the file's own extends
            for (String parent : parents(node)) {
                layer(target, parent, relativeTo.getOrDefault(resolved, resolved));
            }
            merge(target, (ObjectNode) node, "", label(resolved));
        }
        loading.pop();
    }

    private void merge(ObjectNode target, ObjectNode src, String path, String layer) {
        Iterator<Map.Entry<String, JsonNode>> it = src.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String key = e.getKey();
            String p = path.isEmpty() ? key : path + "." + key;
            if (path.isEmpty() && (key.equals("extends") || key.equals("fleet") || key.equals("rollout"))) {
                continue;
            }
            JsonNode value = e.getValue();
            JsonNode existing = target.get(key);
            if (value.isObject() && value.path("override").asBoolean(false)) {
                ObjectNode copy = ((ObjectNode) value).deepCopy();
                copy.remove("override");
                if (existing != null) {
                    overrides.add(layer + " overrides " + p);
                }
                target.set(key, copy);
                setOrigin(p, layer);
            } else if (value.isObject() && existing != null && existing.isObject()) {
                merge((ObjectNode) existing, (ObjectNode) value, p, layer);
            } else if (value.isArray() && existing != null && existing.isArray() && !REPLACED_LISTS.contains(p)) {
                ArrayNode arr = (ArrayNode) existing;
                Set<JsonNode> present = new HashSet<>();
                arr.forEach(present::add);
                value.forEach(v -> {
                    if (present.add(v)) {
                        origin.put(p + "[" + arr.size() + "]", layer);
                        arr.add(v);
                    }
                });
            } else {
                if (existing != null && !existing.equals(value) && !label(DEFAULTS).equals(source(p))) {
                    overrides.add(layer + " overrides " + p + " (was " + existing + " from " + source(p) + ")");
                }
                target.set(key, value.deepCopy());
                setOrigin(p, layer);
            }
        }
    }

    /** {@code path} now comes whole from {@code layer}: what earlier layers set below it is gone. */
    private void setOrigin(String path, String layer) {
        origin.keySet().removeIf(k -> k.startsWith(path + ".") || k.startsWith(path + "["));
        origin.put(path, layer);
    }

    /**
     * The layers a file extends, in order: the fleet's company.yaml ({@code fleet: <name>}, #124), then each
     * {@code extends:} entry (a scalar is one entry). An entry {@code fleet:<name>} (#133) is that fleet's company.yaml,
     * found by name on this machine; a company.yaml already in the list is not layered again (a member with both
     * {@code fleet:} and {@code extends: [fleet:<name>]}). Fleet layers come back as absolute paths.
     */
    private List<String> parents(JsonNode node) {
        List<String> out = new ArrayList<>();
        String fleet = node.path("fleet").asText("").strip();
        // #236: a member pinned to a policy version takes that version only, not also the fleet folder's current file
        boolean pinned = entries(node.path("extends")).stream().anyMatch(e -> fleet.equals(dev.logast.fleet.FleetExtends.name(e))
                && dev.logast.fleet.FleetExtends.pin(e) != null);
        String member = pinned ? null : fleetLayer(fleet, "fleet: ");
        if (member != null) {
            out.add(member);
        }
        for (String entry : entries(node.path("extends"))) {
            String name = dev.logast.fleet.FleetExtends.name(entry);
            if (name == null) {
                out.add(entry);
                continue;
            }
            String pin = dev.logast.fleet.FleetExtends.pin(entry);
            String company = pin == null ? fleetLayer(name, "extends: " + dev.logast.fleet.FleetExtends.PREFIX)
                    : pinnedLayer(name, pin);
            if (company != null && !out.contains(company)) {
                out.add(company);
            }
        }
        return out;
    }

    /** The entries of {@code extends:}: a list, or one string. */
    private static List<String> entries(JsonNode extendsNode) {
        List<String> out = new ArrayList<>();
        if (extendsNode.isTextual()) {
            if (!extendsNode.asText().isBlank()) {
                out.add(extendsNode.asText().strip());
            }
        } else {
            extendsNode.forEach(e -> out.add(e.asText()));
        }
        return out;
    }

    /**
     * #124: the fleet {@code name}'s company.yaml, found by name on this machine ({@link dev.logast.fleet.FleetHome});
     * null without a name or when the fleet has no company.yaml yet. A fleet this machine does not know is a warning:
     * the service's own rules still apply.
     */
    private String fleetLayer(String name, String how) {
        String company = fleetCompany(name);
        if (company == null || Files.isRegularFile(Path.of(company))) {
            return company;
        }
        Path dir = Path.of(company).getParent();
        if (!dev.logast.fleet.NamedFleet.exists(dir)) {
            String w = how + name.strip() + " is not known on this machine (no fleet folder at " + dir + "): its company.yaml "
                    + "is not applied; `logast fleet join " + name.strip() + "` sets it up";
            if (!unpinned.contains(w)) {
                unpinned.add(w);
            }
        }
        return null;
    }

    /**
     * #236: the company.yaml of the fleet's policy version {@code pin}, read from git at the tag; a version this machine
     * cannot read is a warning, never another version: the service's own rules still apply.
     */
    private String pinnedLayer(String name, String pin) {
        Path company = dev.logast.fleet.FleetExtends.company(name.strip(), pin);
        if (Files.isRegularFile(company)) {
            return company.toString();
        }
        String w = "extends: fleet:" + name.strip() + "@" + pin + " is not readable on this machine (the fleet's folder is "
                + "no git repo with that tag): its company.yaml is not applied";
        if (!unpinned.contains(w)) {
            unpinned.add(w);
        }
        return null;
    }

    /** The company.yaml path of the fleet {@code name}, whether it exists or not; null without a name. */
    private static String fleetCompany(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return dev.logast.fleet.FleetExtends.company(name.strip()).toString();
    }

    private static String resolve(String location, String from) {
        if (location.startsWith("classpath:") || location.startsWith("http://") || location.startsWith("https://")) {
            return location;
        }
        if (location.equals("~") || location.startsWith("~/")) {   // extends: [~/.logast/company.yaml] (#113)
            location = System.getProperty("user.home") + location.substring(1);
        }
        Path p = Path.of(location);
        if (!p.isAbsolute() && from != null && !from.startsWith("classpath:") && !from.startsWith("http")) {
            p = Path.of(from).getParent().resolve(location);
        }
        return p.toAbsolutePath().normalize().toString();
    }

    private JsonNode read(String location) {
        try {
            if (location.startsWith("classpath:")) {
                try (InputStream in = PolicyLoader.class.getResourceAsStream("/" + location.substring("classpath:".length()))) {
                    if (in == null) {
                        throw new IllegalArgumentException("policy not found on classpath: " + location);
                    }
                    return YAML.readTree(in);
                }
            }
            if (location.startsWith("http://") || location.startsWith("https://")) {
                // #56: https://…/policy.yaml#sha256=<hex> pins the content: a changed file fails instead of changing rules
                int hash = location.indexOf("#sha256=");
                String url = hash < 0 ? location : location.substring(0, hash);
                HttpResponse<String> r = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                        .send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).build(),
                                HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() / 100 != 2) {
                    throw new IllegalArgumentException("cannot fetch policy " + url + ": HTTP " + r.statusCode());
                }
                String actual = sha256(r.body());
                if (hash >= 0 && !location.substring(hash + "#sha256=".length()).equalsIgnoreCase(actual)) {
                    throw new IllegalArgumentException("policy " + url + " changed: its sha256 is " + actual + ", extends pins "
                            + location.substring(hash + "#sha256=".length()) + "; review the change, then pin the new hash");
                }
                if (hash < 0) {
                    unpinned.add("extends: " + url + " is not pinned, so a change there changes this service's rules unseen; "
                            + "pin it: " + url + "#sha256=" + actual);
                }
                return YAML.readTree(r.body());
            }
            return YAML.readTree(Files.readString(Path.of(location)));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read policy " + location, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static String sha256(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String label(String resolved) {
        if (resolved.startsWith("classpath:") || resolved.startsWith("http")) {
            return resolved;
        }
        return Path.of(resolved).getFileName().toString();
    }
}
