package dev.logast.fleet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * #147: a fleet kept in a git repo lists its members by URL in {@code repos.yaml}; each is a shallow clone in
 * {@code <fleet>/.cache/repos/<name>}, updated to the newest commit of its branch before a refresh, and scanned as an
 * added member (read-only, #132).
 */
public final class FleetRepos {
    public static final String FILE = "repos.yaml";
    public static final String CACHE = ".cache/repos";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** @param branch null: the repo's default branch */
    public record Repo(String name, String url, String branch) {
    }

    private FleetRepos() {
    }

    /** The fleet's name as repos.yaml states it ({@code fleet: <name>}), else null. */
    public static String fleetName(Path fleet) {
        JsonNode n = read(fleet);
        String name = n == null ? "" : n.path("fleet").asText("").strip();
        return name.isEmpty() ? null : name;
    }

    /** The repos of repos.yaml; empty without the file. An entry is a URL, or {url, name?, branch?}. */
    public static List<Repo> list(Path fleet) {
        JsonNode n = read(fleet);
        List<Repo> out = new ArrayList<>();
        if (n == null) {
            return out;
        }
        for (JsonNode e : n.path("repos")) {
            String url = e.isTextual() ? e.asText() : e.path("url").asText("");
            if (url.isBlank()) {
                continue;
            }
            String name = e.isObject() && e.hasNonNull("name") ? e.get("name").asText() : nameOf(url);
            String branch = e.isObject() && e.hasNonNull("branch") ? e.get("branch").asText() : null;
            out.add(new Repo(name, url.strip(), branch));
        }
        return out;
    }

    private static JsonNode read(Path fleet) {
        Path f = fleet.resolve(FILE);
        if (!Files.isRegularFile(f)) {
            return null;
        }
        try {
            return YAML.readTree(Files.readString(f));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + f + ": " + e.getMessage(), e);
        }
    }

    /** {@code git@github.com:org/payments.git}, {@code https://github.com/org/payments} → payments. */
    static String nameOf(String url) {
        String s = url.strip().replaceAll("/+$", "");
        s = s.substring(Math.max(s.lastIndexOf('/'), s.lastIndexOf(':')) + 1);
        return s.endsWith(".git") ? s.substring(0, s.length() - 4) : s;
    }

    /**
     * The repo's clone, brought to the newest commit of its branch: a shallow clone the first time, then a shallow fetch
     * and a hard reset (the clone is logast's: nothing in it is kept). Throws with git's message when git fails.
     */
    public static Path sync(Path fleet, Repo repo) {
        Path dir = fleet.resolve(CACHE).resolve(repo.name());
        try {
            if (Files.isDirectory(dir.resolve(".git"))) {
                git(dir, "fetch", "--quiet", "--depth", "1", "origin", repo.branch() != null ? repo.branch() : "HEAD");
                git(dir, "reset", "--quiet", "--hard", "FETCH_HEAD");
                git(dir, "clean", "--quiet", "-ffdx");
            } else {
                Files.createDirectories(dir.getParent());
                List<String> clone = new ArrayList<>(List.of("clone", "--quiet", "--depth", "1"));
                if (repo.branch() != null) {
                    clone.addAll(List.of("--branch", repo.branch()));
                }
                clone.addAll(List.of(repo.url(), dir.toString()));
                git(dir.getParent(), clone.toArray(String[]::new));
            }
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void git(Path in, String... args) throws IOException {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(in.toFile()).redirectErrorStream(true).start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes()).strip();
        try {
            if (!p.waitFor(10, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IOException("git " + args[0] + " took longer than 10 minutes");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        if (p.exitValue() != 0) {
            throw new IOException("git " + args[0] + " failed: " + out);
        }
    }

    /** The repos.yaml {@code fleet init} writes. */
    public static String template(String name) {
        return """
                # The members of fleet %s, by repo URL. `logast fleet . --refresh` (and the workflow) clones each one
                # (shallow, newest commit) into .cache/repos/<name> and scans it read-only: nothing is written into the repos.
                #   - a URL: https://github.com/your-org/payments, git@github.com:your-org/payments.git
                #   - or an entry with a name (default: the repo's name) and a branch (default: the repo's default branch):
                #       - url: git@github.com:your-org/orders.git
                #         name: orders
                #         branch: develop
                fleet: %s
                repos:
                #  - https://github.com/your-org/payments
                #  - git@github.com:your-org/orders.git
                """.formatted(name, name);
    }
}
