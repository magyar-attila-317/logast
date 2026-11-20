package dev.logast.kb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.Map;

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

    /**
     * What resolution reads: the environment and the home folder. {@code logast.home} (a system property) isolates
     * tests from the real home, as logast's fleet commands do.
     */
    public record Env(Map<String, String> env, Path home) {
        public static Env current() {
            String isolated = System.getProperty("logast.home");
            return isolated != null ? new Env(Map.of(), Path.of(isolated)) : new Env(System.getenv(), Path.of(System.getProperty("user.home")));
        }

        /** The user config: {@code $XDG_CONFIG_HOME/logast/config.yaml}, else {@code ~/.config/logast/config.yaml}. */
        public Path configFile() {
            String xdg = env.get("XDG_CONFIG_HOME");
            Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : home.resolve(".config");
            return base.resolve("logast").resolve("config.yaml");
        }
    }

    public static Resolved resolve(List<Path> options, Env env) {
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

    private static Path expand(Env env, String path) {
        String p = path.strip();
        return (p.equals("~") || p.startsWith("~/") ? env.home().resolve(p.equals("~") ? "" : p.substring(2)) : Path.of(p))
                .toAbsolutePath().normalize();
    }
}
