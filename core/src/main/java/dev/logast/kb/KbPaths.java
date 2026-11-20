package dev.logast.kb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.logast.fleet.FleetHome;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * #156: where {@code logast mcp} finds facts: {@code --kb} options, else {@code LOGAST_KB} (paths separated like
 * {@code PATH}), else {@code kb.paths} in the user config ({@code ~/.config/logast/config.yaml}).
 */
public final class KbPaths {
    public static final String ENV = "LOGAST_KB";

    private KbPaths() {
    }

    public record Resolved(List<Path> paths, String source) {
    }

    public static Resolved resolve(List<Path> options, FleetHome.Env env) {
        if (options != null && !options.isEmpty()) {
            return new Resolved(options.stream().map(p -> expand(env, p.toString())).toList(), "--kb");
        }
        String e = env.env().get(ENV);
        if (e != null && !e.isBlank()) {
            List<Path> out = new ArrayList<>();
            for (String part : e.split(File.pathSeparator)) {
                if (!part.isBlank()) {
                    out.add(expand(env, part));
                }
            }
            return new Resolved(out, ENV);
        }
        Path config = env.configFile();
        if (Files.isRegularFile(config)) {
            try {
                JsonNode paths = new ObjectMapper(new YAMLFactory()).readTree(config.toFile()).path("kb").path("paths");
                List<Path> out = new ArrayList<>();
                if (paths.isArray()) {
                    paths.forEach(p -> out.add(expand(env, p.asText())));
                } else if (!paths.asText("").isBlank()) {
                    out.add(expand(env, paths.asText()));
                }
                if (!out.isEmpty()) {
                    return new Resolved(out, "kb.paths in " + config);
                }
            } catch (IOException ex) {
                return new Resolved(List.of(), "unreadable " + config + ": " + ex.getMessage());
            }
        }
        return new Resolved(List.of(), "none");
    }

    private static Path expand(FleetHome.Env env, String path) {
        String p = path.strip();
        return (p.equals("~") || p.startsWith("~/") ? env.home().resolve(p.equals("~") ? "" : p.substring(2)) : Path.of(p))
                .toAbsolutePath().normalize();
    }
}
