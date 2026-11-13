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
 *   <li>{@code packs} and {@code mdc.ambientKeys} are replaced, not appended (they are selections);</li>
 *   <li>every explicit override, and every scalar a later layer changes after a non-default layer set it, is
 *       recorded in {@link Policy#overrides()} so the standard's deviations stay visible.</li>
 * </ul>
 */
public final class PolicyLoader {
    static final String DEFAULTS = "classpath:logast-defaults.yaml";
    private static final Set<String> REPLACED_LISTS = Set.of("packs", "mdc.ambientKeys");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final List<String> overrides = new ArrayList<>();
    private final Map<String, String> origin = new HashMap<>();
    private final Deque<String> loading = new ArrayDeque<>();

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
        PolicyLoader loader = new PolicyLoader();
        ObjectNode merged = JsonNodeFactory.instance.objectNode();
        loader.layer(merged, DEFAULTS, null);
        Path file = explicit != null ? explicit : root == null ? null : defaultFile(root);
        if (file != null && Files.exists(file)) {
            loader.layer(merged, file.toAbsolutePath().normalize().toString(), null);
        } else if (explicit != null) {
            throw new IllegalArgumentException("policy file not found: " + explicit);
        }
        merged.remove("extends");
        merged.set("overrides", JsonNodeFactory.instance.arrayNode().addAll(loader.overrides.stream().map(TextNode::new).toList()));
        try {
            return YAML.treeToValue(merged, Policy.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid policy (" + file + "): " + e.getMessage(), e);
        }
    }

    private void layer(ObjectNode target, String location, String from) {
        String resolved = resolve(location, from);
        if (loading.contains(resolved)) {
            throw new IllegalArgumentException("extends cycle: " + loading + " -> " + resolved);
        }
        loading.push(resolved);
        JsonNode node = read(resolved);
        if (node != null && node.isObject()) {
            for (JsonNode parent : node.path("extends")) {
                layer(target, parent.asText(), resolved);
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
            if (path.isEmpty() && key.equals("extends")) {
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
                origin.put(p, layer);
            } else if (value.isObject() && existing != null && existing.isObject()) {
                merge((ObjectNode) existing, (ObjectNode) value, p, layer);
            } else if (value.isArray() && existing != null && existing.isArray() && !REPLACED_LISTS.contains(p)) {
                ArrayNode arr = (ArrayNode) existing;
                Set<JsonNode> present = new HashSet<>();
                arr.forEach(present::add);
                value.forEach(v -> {
                    if (present.add(v)) {
                        arr.add(v);
                    }
                });
            } else {
                if (existing != null && !existing.equals(value) && !label(DEFAULTS).equals(origin.getOrDefault(p, label(DEFAULTS)))) {
                    overrides.add(layer + " overrides " + p + " (was " + existing + " from " + origin.get(p) + ")");
                }
                target.set(key, value.deepCopy());
                origin.put(p, layer);
            }
        }
    }

    private static String resolve(String location, String from) {
        if (location.startsWith("classpath:") || location.startsWith("http://") || location.startsWith("https://")) {
            return location;
        }
        Path p = Path.of(location);
        if (!p.isAbsolute() && from != null && !from.startsWith("classpath:") && !from.startsWith("http")) {
            p = Path.of(from).getParent().resolve(location);
        }
        return p.toAbsolutePath().normalize().toString();
    }

    private static JsonNode read(String location) {
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
                HttpResponse<String> r = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                        .send(HttpRequest.newBuilder(URI.create(location)).timeout(Duration.ofSeconds(20)).build(),
                                HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() / 100 != 2) {
                    throw new IllegalArgumentException("cannot fetch policy " + location + ": HTTP " + r.statusCode());
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

    private static String label(String resolved) {
        if (resolved.startsWith("classpath:") || resolved.startsWith("http")) {
            return resolved;
        }
        return Path.of(resolved).getFileName().toString();
    }
}
