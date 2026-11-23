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
                String text = dev.logast.parse.SourceText.read(p);
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

    /**
     * Every key {@code file} (an application*.yml/.yaml/.properties) sets, in any of its documents, profile-specific ones
     * included; empty when it cannot be read.
     */
    public static Set<String> allKeys(Path file) {
        Set<String> out = new TreeSet<>();
        try {
            String text = dev.logast.parse.SourceText.read(file);
            if (file.getFileName().toString().endsWith(".properties")) {
                Properties props = new Properties();
                props.load(new StringReader(text));
                props.keySet().forEach(k -> out.add(String.valueOf(k)));
            } else {
                for (JsonNode doc : new ObjectMapper(new YAMLFactory()).readerFor(JsonNode.class).<JsonNode>readValues(text).readAll()) {
                    Map<String, String> flat = new TreeMap<>();
                    flatten("", doc, flat);
                    out.addAll(flat.keySet());
                }
            }
        } catch (IOException | RuntimeException e) {
            // unreadable config: treat as absent
        }
        return out;
    }

    /**
     * The values the service sets only for a profile or platform (#128): {@code application-<profile>.yml|yaml|properties}
     * (also in {@code config/}) and the documents with {@code spring.config.activate.*} in the base files, key → every
     * distinct value in file order. Not the service's value: for a caller that needs a hint (the host a client calls).
     */
    public static Map<String, List<String>> profileValues(Path root) {
        Map<String, List<String>> out = new TreeMap<>();
        Path resources = appResources(root);
        if (resources == null) {
            return out;
        }
        List<Path> files = new ArrayList<>();
        for (Path dir : List.of(resources, resources.resolve("config"))) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> p.getFileName().toString().matches("application(-[^.]+)?\\.(yml|yaml|properties)")).sorted().forEach(files::add);
            } catch (IOException e) {
                // unreadable folder: no hints from it
            }
        }
        for (Path p : files) {
            boolean profileFile = p.getFileName().toString().startsWith("application-");
            try {
                String text = dev.logast.parse.SourceText.read(p);
                List<Map<String, String>> docs = documents(p, text);
                for (Map<String, String> doc : docs) {
                    if (profileFile || doc.keySet().stream().anyMatch(k -> k.startsWith("spring.config.activate."))) {
                        doc.forEach((k, v) -> {
                            List<String> values = out.computeIfAbsent(k, x -> new ArrayList<>());
                            if (!values.contains(v)) {
                                values.add(v);
                            }
                        });
                    }
                }
            } catch (IOException | RuntimeException e) {
                // unreadable config: treat as absent
            }
        }
        return out;
    }

    /**
     * #157: the configuration as one profile sees it: the base ({@link #read}), then {@code application-<profile>.*} (also
     * in {@code config/}) and the base files' documents activated for that profile ({@code spring.config.activate.on-profile}),
     * which override it.
     */
    /**
     * #242: the profiles a service's configuration names, as {@link #forProfile} reads them: an {@code application-<profile>.*}
     * (also in {@code config/}), and a base file's document activated with {@code spring.config.activate.on-profile}
     * (a negated {@code !profile} names none).
     */
    public static List<String> profiles(Path root) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        Path resources = appResources(root);
        if (resources == null) {
            return List.of();
        }
        for (Path dir : List.of(resources, resources.resolve("config"))) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (var s = Files.list(dir)) {
                s.map(p -> p.getFileName().toString()).filter(n -> n.matches("application-([^.]+)\\.(yml|yaml|properties)"))
                        .map(n -> n.replaceAll("application-([^.]+)\\..*", "$1")).forEach(out::add);
            } catch (IOException e) {
                // unreadable: no profiles from it
            }
            for (String f : List.of("application.yml", "application.yaml", "application.properties")) {
                Path p = dir.resolve(f);
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                try {
                    for (Map<String, String> doc : documents(p, dev.logast.parse.SourceText.read(p))) {
                        String on = doc.get("spring.config.activate.on-profile");
                        if (on != null) {
                            java.util.Arrays.stream(on.split(",")).map(String::strip).filter(x -> !x.isEmpty() && !x.startsWith("!"))
                                    .forEach(out::add);
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    // unreadable config: treat as absent
                }
            }
        }
        return List.copyOf(out);
    }

    public static Map<String, String> forProfile(Path root, String profile) {
        Map<String, String> out = new TreeMap<>(read(root));
        Path resources = appResources(root);
        if (resources == null || profile == null) {
            return out;
        }
        List<Path> files = new ArrayList<>();
        for (Path dir : List.of(resources, resources.resolve("config"))) {
            for (String f : List.of("application.yml", "application.yaml", "application.properties",
                    "application-" + profile + ".yml", "application-" + profile + ".yaml", "application-" + profile + ".properties")) {
                if (Files.isRegularFile(dir.resolve(f))) {
                    files.add(dir.resolve(f));
                }
            }
        }
        for (Path p : files) {
            boolean profileFile = p.getFileName().toString().startsWith("application-");
            try {
                String text = dev.logast.parse.SourceText.read(p);
                List<Map<String, String>> docs = documents(p, text);
                for (Map<String, String> doc : docs) {
                    String on = doc.get("spring.config.activate.on-profile");
                    boolean activated = on != null && java.util.Arrays.stream(on.split(",")).map(String::strip).anyMatch(profile::equals);
                    if (profileFile && doc.keySet().stream().noneMatch(k -> k.startsWith("spring.config.activate.")) || activated) {
                        out.putAll(doc);
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
                return dev.logast.parse.SourceText.read(p).contains("@SpringBootApplication");
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

    /**
     * The documents of an application file, each flattened to dotted keys: a .properties file split at {@code #---} /
     * {@code !---}, a YAML file at {@code ---}.
     */
    private static List<Map<String, String>> documents(Path p, String text) throws IOException {
        List<Map<String, String>> docs = new ArrayList<>();
        if (p.toString().endsWith(".properties")) {
            for (String doc : text.split("(?m)^[#!]---\\s*$")) {
                Properties props = new Properties();
                props.load(new StringReader(doc));
                Map<String, String> flat = new TreeMap<>();
                props.forEach((k, v) -> flat.put(String.valueOf(k), String.valueOf(v).trim()));
                docs.add(flat);
            }
        } else {
            for (JsonNode doc : new ObjectMapper(new YAMLFactory()).readerFor(JsonNode.class).<JsonNode>readValues(text).readAll()) {
                Map<String, String> flat = new TreeMap<>();
                flatten("", doc, flat);
                docs.add(flat);
            }
        }
        return docs;
    }
}
