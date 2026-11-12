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

/** Reads the base application.yml/.yaml/.properties of a service into flat dotted keys (profiles are ignored). */
public final class SpringProperties {
    private SpringProperties() {
    }

    public static Map<String, String> read(Path root) {
        Map<String, String> out = new TreeMap<>();
        for (Path resources : resourceDirs(root)) {
            for (String name : List.of("application.properties", "application.yml", "application.yaml")) {
                Path p = resources.resolve(name);
                if (!Files.exists(p)) {
                    continue;
                }
                try {
                    if (name.endsWith(".properties")) {
                        Properties props = new Properties();
                        props.load(new StringReader(Files.readString(p)));
                        props.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v).trim()));
                    } else {
                        for (JsonNode doc : new ObjectMapper(new YAMLFactory()).readerFor(JsonNode.class)
                                .<JsonNode>readValues(Files.readString(p)).readAll()) {
                            flatten("", doc, out);
                        }
                    }
                } catch (IOException e) {
                    // unreadable config: treat as absent
                }
            }
        }
        return out;
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
