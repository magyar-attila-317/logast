package dev.logast.export;

import dev.logast.facts.Edge;
import dev.logast.facts.Evidence;
import dev.logast.facts.FactSink;
import dev.logast.facts.Node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * #162: the libraries a repo publishes: every Gradle project of the repo (the root and {@code settings.gradle(.kts)}
 * includes) that applies {@code maven-publish} or {@code java-library} is {@code library:<group>:<artifact>}, with its
 * version, {@code defines}d by {@code repo:<name>}. Services' {@code depends-on} edges then reach the repo that owns a
 * library.
 */
public final class GradleProjects {
    private static final Pattern INCLUDE = Pattern.compile("(?m)^\\s*include\\s*\\(?([^)\\n]+)\\)?");
    private static final Pattern QUOTED = Pattern.compile("[\"']([^\"']+)[\"']");
    private static final Pattern ROOT_NAME = Pattern.compile("rootProject\\.name\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern GROUP = Pattern.compile("(?m)^\\s*group\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern VERSION = Pattern.compile("(?m)^\\s*version\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern ARTIFACT = Pattern.compile("(?:artifactId|archivesName)\\s*(?:=|\\.set\\()\\s*[\"']([^\"']+)[\"']");
    private static final Pattern PUBLISHES = Pattern.compile("maven-publish|`maven-publish`|java-library|`java-library`");

    /** A Gradle project of the repo. */
    record Project(String path, Path dir, String name) {
    }

    private GradleProjects() {
    }

    static List<Project> projects(Path root) {
        List<Project> out = new ArrayList<>();
        Path settings = first(root, "settings.gradle.kts", "settings.gradle");
        String rootName = root.toAbsolutePath().normalize().getFileName().toString();
        String text = settings == null ? "" : read(settings);
        Matcher rn = ROOT_NAME.matcher(text);
        if (rn.find()) {
            rootName = rn.group(1);
        }
        out.add(new Project(":", root, rootName));
        Matcher inc = INCLUDE.matcher(text);
        while (inc.find()) {
            Matcher q = QUOTED.matcher(inc.group(1));
            while (q.find()) {
                String path = q.group(1).startsWith(":") ? q.group(1).substring(1) : q.group(1);
                Path dir = root.resolve(path.replace(':', '/'));
                out.add(new Project(":" + path, dir, path.substring(path.lastIndexOf(':') + 1)));
            }
        }
        return out;
    }

    /** {@code repo:<name>} and the libraries its projects publish. */
    public static void extract(Path root, String repo, String service, FactSink out) {
        Map<String, String> props = gradleProperties(root);
        Path rootBuild = first(root, "build.gradle.kts", "build.gradle");
        String rootText = rootBuild == null ? "" : read(rootBuild);
        String rootGroup = find(GROUP, rootText, props.get("group"));
        String rootVersion = find(VERSION, rootText, props.get("version"));
        String repoId = "repo:" + repo;
        out.node(new Node(repoId, "repo", null, Map.of(), Evidence.at(rootBuild == null ? "settings.gradle.kts" : rel(root, rootBuild), null)));
        if (service != null) {
            out.edge(Edge.extracted(repoId, "has", "service:" + service, null, Evidence.at(rootBuild == null ? "." : rel(root, rootBuild), null)));
        }
        for (Project p : projects(root)) {
            Path build = first(p.dir(), "build.gradle.kts", "build.gradle");
            if (build == null) {
                continue;
            }
            String text = read(build);
            if (!PUBLISHES.matcher(text).find()) {
                continue;
            }
            String group = find(GROUP, text, rootGroup);
            if (group == null) {
                continue;   // no coordinates: not something others depend on by name
            }
            String artifact = find(ARTIFACT, text, p.name());
            String version = find(VERSION, text, rootVersion);
            Map<String, Object> attrs = new TreeMap<>();
            attrs.put("module", p.path());
            if (version != null) {
                attrs.put("version", version);
            }
            Evidence at = Evidence.at(rel(root, build), null);
            String id = "library:" + group + ":" + artifact;
            out.node(new Node(id, "library", null, attrs, at));
            out.edge(Edge.extracted(repoId, "defines", id, version == null ? null : Map.of("version", version), at));
        }
    }

    private static Map<String, String> gradleProperties(Path root) {
        Map<String, String> out = new LinkedHashMap<>();
        Path f = root.resolve("gradle.properties");
        if (Files.isRegularFile(f)) {
            Properties p = new Properties();
            try (var in = Files.newBufferedReader(f)) {
                p.load(in);
            } catch (IOException e) {
                return out;
            }
            p.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v).strip()));
        }
        return out;
    }

    private static String find(Pattern p, String text, String fallback) {
        Matcher m = p.matcher(text);
        if (m.find() && !m.group(1).contains("$")) {
            return m.group(1);
        }
        return fallback;
    }

    private static Path first(Path dir, String... names) {
        for (String n : names) {
            if (Files.isRegularFile(dir.resolve(n))) {
                return dir.resolve(n);
            }
        }
        return null;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            return "";
        }
    }

    private static String rel(Path root, Path p) {
        return root.toAbsolutePath().normalize().relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }
}
