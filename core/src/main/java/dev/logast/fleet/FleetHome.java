package dev.logast.fleet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Where named fleets live on this machine (#124). The fleets folder, in this order: {@code --fleets-dir} on the command
 * → {@code LOGAST_FLEETS} → {@code fleets.dir} in the user config ({@code $XDG_CONFIG_HOME/logast/config.yaml}, else
 * {@code ~/.config/logast/config.yaml}) → {@code $XDG_DATA_HOME/logast/fleets}, else {@code ~/.local/share/logast/fleets}.
 * A fleet's own folder is the path the user config remembers for it ({@code fleets.paths.<name>}, written by
 * {@code logast fleet create <name> --dir}), else {@code <fleets folder>/<name>}. A service's logast.yaml only names
 * its fleet; each machine resolves the folder here.
 */
public final class FleetHome {
    /** The environment variable naming the fleets folder. */
    public static final String ENV = "LOGAST_FLEETS";
    /** Set by {@code --fleets-dir}: the fleets folder for this run (the commands share one process). */
    public static final String DIR_PROPERTY = "logast.fleets.dir";
    /**
     * Tests: the home folder to use instead of the user's; the process environment is then ignored (no XDG_*, no
     * LOGAST_FLEETS), so a test never reads or writes the real home.
     */
    public static final String HOME_PROPERTY = "logast.home";
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER).enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));

    private FleetHome() {
    }

    /**
     * What resolution reads.
     *
     * @param option the {@code --fleets-dir} value, or null
     * @param env    the environment ({@code LOGAST_FLEETS}, {@code XDG_CONFIG_HOME}, {@code XDG_DATA_HOME})
     * @param home   the user's home folder
     */
    public record Env(String option, Map<String, String> env, Path home) {
        /** This process: {@link #DIR_PROPERTY}, the environment and the home folder ({@link #HOME_PROPERTY} isolates). */
        public static Env current() {
            String isolated = System.getProperty(HOME_PROPERTY);
            return isolated != null
                    ? new Env(System.getProperty(DIR_PROPERTY), Map.of(), Path.of(isolated))
                    : new Env(System.getProperty(DIR_PROPERTY), System.getenv(), Path.of(System.getProperty("user.home")));
        }

        /** The user config: {@code $XDG_CONFIG_HOME/logast/config.yaml}, else {@code ~/.config/logast/config.yaml}. */
        public Path configFile() {
            String xdg = env.get("XDG_CONFIG_HOME");
            Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : home.resolve(".config");
            return base.resolve("logast").resolve("config.yaml");
        }

        Path expand(String path) {
            String p = path.strip();
            return (p.equals("~") || p.startsWith("~/") ? home.resolve(p.equals("~") ? "" : p.substring(2)) : Path.of(p))
                    .toAbsolutePath().normalize();
        }
    }

    /** A resolved folder and what decided it (for {@code logast fleet list}). */
    public record Location(Path dir, String source) {
    }

    /** The folder named fleets live in, and which setting gave it. */
    public static Location fleetsDir(Env e) {
        if (e.option() != null && !e.option().isBlank()) {
            return new Location(e.expand(e.option()), "--fleets-dir");
        }
        String env = e.env().get(ENV);
        if (env != null && !env.isBlank()) {
            return new Location(e.expand(env), ENV);
        }
        String configured = config(e).path("fleets").path("dir").asText("");
        if (!configured.isBlank()) {
            return new Location(e.expand(configured), "fleets.dir in " + e.configFile());
        }
        String data = e.env().get("XDG_DATA_HOME");
        Path base = data != null && !data.isBlank() ? Path.of(data) : e.home().resolve(".local").resolve("share");
        return new Location(base.resolve("logast").resolve("fleets").toAbsolutePath().normalize(), "default");
    }

    /** The folder of the fleet {@code name}: the one the user config remembers for it, else under {@link #fleetsDir}. */
    public static Location fleetDir(Env e, String name) {
        String own = config(e).path("fleets").path("paths").path(name).asText("");
        if (!own.isBlank()) {
            return new Location(e.expand(own), "fleets.paths." + name + " in " + e.configFile());
        }
        Location home = fleetsDir(e);
        return new Location(home.dir().resolve(name), home.source());
    }

    /** The fleet folders the user config remembers ({@code fleets.paths}): name → folder. */
    public static Map<String, Path> remembered(Env e) {
        Map<String, Path> out = new java.util.TreeMap<>();
        config(e).path("fleets").path("paths").fields().forEachRemaining(f -> out.put(f.getKey(), e.expand(f.getValue().asText())));
        return out;
    }

    /** Remembers {@code dir} as the folder of fleet {@code name} in the user config (other settings kept). */
    public static void remember(Env e, String name, Path dir) {
        JsonNode c = config(e);
        ObjectNode root = c.isObject() ? (ObjectNode) c : YAML.createObjectNode();
        ObjectNode fleets = root.path("fleets").isObject() ? (ObjectNode) root.get("fleets") : root.putObject("fleets");
        ObjectNode paths = fleets.path("paths").isObject() ? (ObjectNode) fleets.get("paths") : fleets.putObject("paths");
        paths.put(name, dir.toAbsolutePath().normalize().toString());
        try {
            Files.createDirectories(e.configFile().getParent());
            YAML.writeValue(e.configFile().toFile(), root);
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot write " + e.configFile(), ex);
        }
    }

    /** A fleet name: letters, digits, dot, dash and underscore (it is a folder name and goes into logast.yaml). */
    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    private static JsonNode config(Env e) {
        Path f = e.configFile();
        if (!Files.isRegularFile(f)) {
            return YAML.createObjectNode();
        }
        try {
            JsonNode n = YAML.readTree(Files.readString(f));
            return n == null ? YAML.createObjectNode() : n;
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot read the logast user config " + f + ": " + ex.getMessage(), ex);
        }
    }
}
