package dev.logast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * The service's base configuration as Spring Boot reads it, flattened to dotted keys (#47). Only the module that holds
 * the {@code @SpringBootApplication} class counts; within it, {@code application.yml|yaml} then
 * {@code application.properties} (it takes precedence over YAML in the same location), then the same in {@code config/}
 * (higher precedence). Within a file, documents apply top to bottom; documents with {@code spring.config.activate.*}
 * (profile- or platform-specific) are left out: a value set only for one profile is not the service's value.
 * Source: Spring Boot reference, "Externalized Configuration" (docs.spring.io/spring-boot/reference/features/external-config.html).
 */
public final class SpringProperties {
    private SpringProperties() {
    }

    /** Config files in ascending precedence, relative to the app's resources folder. */
    public static final List<String> FILES = List.of("application.yml", "application.yaml", "application.properties",
            "config/application.yml", "config/application.yaml", "config/application.properties");

    public static Map<String, String> read(Path root) {
        Map<String, String> out = new TreeMap<>();
        Path resources = appResources(root);
        if (resources == null) {
            return out;
        }
        for (String name : FILES) {
            Path p = resources.resolve(name);
            if (!Files.exists(p)) {
                continue;
            }
            try {
                String text = Files.readString(p);
                if (name.endsWith(".properties")) {
                    for (String doc : text.split("(?m)^[#!]---\\s*$")) {
                        Properties props = new Properties();
                        props.load(new StringReader(doc));
                        Map<String, String> flat = new TreeMap<>();
                        props.forEach((k, v) -> flat.put(String.valueOf(k), String.valueOf(v).trim()));
                        putBase(flat, out);
                    }
                } else {
                    for (JsonNode doc : new ObjectMapper(new YAMLFactory()).readerFor(JsonNode.class)
                            .<JsonNode>readValues(text).readAll()) {
                        Map<String, String> flat = new TreeMap<>();
                        flatten("", doc, flat);
                        putBase(flat, out);
                    }
                }
            } catch (IOException | RuntimeException e) {
                // unreadable config: treat as absent
            }
        }
        return out;
    }

    /** A document that applies always (no spring.config.activate.*) overrides what came before. */
    private static void putBase(Map<String, String> doc, Map<String, String> out) {
        if (doc.keySet().stream().noneMatch(k -> k.startsWith("spring.config.activate."))) {
            out.putAll(doc);
        }
    }

    /**
     * The resources folder of the module that holds the @SpringBootApplication class (the first one, sorted, when
     * several do); with none, the first resources folder. Null when there is none at all.
     */
    public static Path appResources(Path root) {
        List<Path> dirs = resourceDirs(root);
        for (Path d : dirs) {
            Path java = d.getParent().resolve("java");
            if (Files.isDirectory(java) && hasBootApplication(java)) {
                return d;
            }
        }
        return dirs.isEmpty() ? null : dirs.getFirst();
    }

    /** java folder -> (fingerprint of its .java files, holds @SpringBootApplication); rules ask several times per scan. */
    private static final Map<Path, Map.Entry<Long, Boolean>> BOOT_APPLICATION = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean hasBootApplication(Path java) {
        List<Path> sources;
        long fingerprint = 17;
        try (Stream<Path> s = Files.walk(java)) {
            sources = s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
            for (Path p : sources) {
                fingerprint = 31 * (31 * fingerprint + p.hashCode()) + Files.getLastModifiedTime(p).toMillis();
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
        Path key = java.toAbsolutePath().normalize();
        Map.Entry<Long, Boolean> cached = BOOT_APPLICATION.get(key);
        if (cached != null && cached.getKey() == fingerprint) {
            return cached.getValue();
        }
        boolean found = sources.stream().anyMatch(p -> {
            try {
                return Files.readString(p).contains("@SpringBootApplication");
            } catch (IOException | RuntimeException e) {
                return false;
            }
        });
        BOOT_APPLICATION.put(key, Map.entry(fingerprint, found));
        return found;
    }

    public static List<Path> resourceDirs(Path root) {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root, 6)) {
            s.filter(Files::isDirectory)
                    .filter(d -> d.endsWith(Path.of("src", "main", "resources")))
                    .filter(d -> d.toString().indexOf("/build/") < 0 && d.toString().indexOf("/node_modules/") < 0)
                    .sorted().forEach(out::add);
        } catch (IOException e) {
            return out;
        }
        return out;
    }

    private static void flatten(String prefix, JsonNode node, Map<String, String> out) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> flatten(prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), e.getValue(), out));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                flatten(prefix + "[" + i + "]", node.get(i), out);
            }
        } else {
            out.put(prefix, node.asText());
        }
    }
}
