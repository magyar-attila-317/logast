package dev.logast.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.logast.facts.Evidence;
import dev.logast.facts.FactSink;
import dev.logast.facts.Node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * #160: a Helm repo's facts per environment. With the helmfile CLI: {@code helmfile -e <env> list} for every release
 * and {@code helmfile -e <env> template} for the ones whose chart is in the repo plus those asked for (third-party
 * charts that carry company resources); without it, manifests rendered elsewhere ({@code <dir>/<env>.yaml}). The
 * manifests become facts through {@link HelmManifests}.
 */
public final class HelmExtractor implements Extractor {
    public static final String ID = "helm";
    private static final List<String> HELMFILES = List.of("helmfile.yaml", "helmfile.yaml.gotmpl", "helmfile.yml");
    private static final Pattern ENVIRONMENTS = Pattern.compile("(?m)^environments:\\s*\\n((?:[ \\t]+.*\\n?|\\s*\\n)+)");
    private static final Pattern ENV_KEY = Pattern.compile("(?m)^  ([A-Za-z0-9][\\w.-]*):");

    private final List<String> envs;
    private final List<String> render;
    private final Path rendered;
    private final List<String> problems = new ArrayList<>();
    private final Map<String, String> namespaces = new LinkedHashMap<>();

    /**
     * @param envs     the environments (empty: the helmfile's {@code environments:})
     * @param render   third-party releases to render too (their charts carry topics, users, dashboards)
     * @param rendered a folder of {@code <env>.yaml} manifests rendered elsewhere, instead of the CLI; null to use it
     */
    public HelmExtractor(List<String> envs, List<String> render, Path rendered) {
        this.envs = envs == null ? List.of() : envs;
        this.render = render == null ? List.of() : render;
        this.rendered = rendered;
    }

    @Override
    public String id() {
        return ID;
    }

    public List<String> problems() {
        return List.copyOf(problems);
    }

    /** The repo's helmfile, or null when it has none (then it is no Helm repo for this extractor). */
    public static Path helmfile(Path root) {
        return HELMFILES.stream().map(root::resolve).filter(Files::isRegularFile).findFirst().orElse(null);
    }

    /** The environments a helmfile declares (its top-level {@code environments:} keys). */
    static List<String> environments(String helmfile) {
        List<String> out = new ArrayList<>();
        Matcher m = ENVIRONMENTS.matcher(helmfile);
        if (m.find()) {
            Matcher k = ENV_KEY.matcher(m.group(1));
            while (k.find()) {
                out.add(k.group(1));
            }
        }
        return out;
    }

    @Override
    public void extract(Path root, FactSink out) {
        Path file = helmfile(root);
        List<String> environments = envs;
        if (environments.isEmpty() && rendered != null) {
            environments = renderedEnvs();
        }
        if (environments.isEmpty() && file != null) {
            environments = environments(read(file));
        }
        if (environments.isEmpty()) {
            environments = List.of("default");
        }
        Map<String, Path> charts = charts(root);
        for (String env : environments) {
            String manifests;
            if (rendered != null) {
                Path f = rendered.resolve(env + ".yaml");
                if (!Files.isRegularFile(f)) {
                    problems.add(env + ": no " + f);
                    continue;
                }
                manifests = read(f);
            } else {
                if (file == null) {
                    problems.add("no helmfile.yaml in " + root + " (or give --rendered)");
                    return;
                }
                manifests = helmfile(root, file, env, out);
                if (manifests == null) {
                    continue;
                }
            }
            try {
                new HelmManifests(env, source -> sourceEvidence(root, charts, source), name -> valuesLine(root, name, rendered))
                        .withReleaseNamespaces(namespaces).extract(manifests, out);
            } catch (IOException | RuntimeException e) {
                problems.add(env + ": the manifests could not be read: " + e.getMessage());
            }
        }
    }

    private List<String> renderedEnvs() {
        try (Stream<Path> s = Files.list(rendered)) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".yaml")).map(n -> n.substring(0, n.length() - 5)).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Lists the releases (all become facts) and renders the local and requested ones; null when helmfile failed. */
    private String helmfile(Path root, Path file, String env, FactSink out) {
        String json = run(root, env, List.of("helmfile", "-f", file.toString(), "-e", env, "list", "--output", "json"));
        if (json == null) {
            return null;
        }
        List<String> selected = new ArrayList<>();
        try {
            for (JsonNode r : new ObjectMapper().readTree(json)) {
                String name = r.path("name").asText();
                String chart = r.path("chart").asText();
                boolean local = chart.startsWith("./") || chart.startsWith("../") || chart.startsWith("/")
                        || Files.isDirectory(file.getParent().resolve(chart));
                boolean rendered = r.path("enabled").asBoolean(true) && (local || render.contains(name));
                Map<String, Object> attrs = new TreeMap<>();
                attrs.put("chart", chart);
                attrs.put("rendered", rendered);
                if (!r.path("namespace").asText("").isBlank()) {
                    namespaces.put(name, r.path("namespace").asText());
                }
                for (String f : List.of("namespace", "version")) {
                    if (!r.path(f).asText("").isBlank()) {
                        attrs.put(f, r.path(f).asText());
                    }
                }
                if (!r.path("enabled").asBoolean(true)) {
                    attrs.put("enabled", false);
                }
                out.node(new Node("release:" + name, "release", env, attrs, Evidence.at(rel(root, file), null)));
                if (rendered) {
                    selected.add(name);
                }
            }
        } catch (IOException e) {
            problems.add(env + ": helmfile list printed no JSON: " + e.getMessage());
            return null;
        }
        if (selected.isEmpty()) {
            return "";
        }
        List<String> cmd = new ArrayList<>(List.of("helmfile", "-f", file.toString(), "-e", env, "template"));
        for (String s : selected) {
            cmd.add("--selector");
            cmd.add("name=" + s);
        }
        return run(root, env, cmd);
    }

    private String run(Path root, String env, List<String> cmd) {
        try {
            Path err = Files.createTempFile("logast-helmfile", ".err");
            Process p = new ProcessBuilder(cmd).directory(root.toFile()).redirectError(err.toFile()).start();
            String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(10, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                problems.add(env + ": " + String.join(" ", cmd.subList(0, Math.min(5, cmd.size()))) + " took longer than 10 minutes");
                return null;
            }
            String stderr = Files.readString(err);
            Files.deleteIfExists(err);
            if (p.exitValue() != 0) {
                problems.add(env + ": " + cmd.get(0) + " " + cmd.get(cmd.size() > 5 ? 5 : cmd.size() - 1) + " failed: "
                        + stderr.lines().filter(l -> !l.isBlank()).reduce((a, b) -> b).orElse("exit " + p.exitValue()));
                return null;
            }
            return stdout;
        } catch (IOException e) {
            problems.add(env + ": cannot run " + cmd.get(0) + " (is it installed and on PATH?): " + e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Chart name → its folder in the repo (every Chart.yaml, subcharts of umbrella charts too). */
    static Map<String, Path> charts(Path root) {
        Map<String, Path> out = new LinkedHashMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(p -> p.getFileName().toString().equals("Chart.yaml") && !p.toString().contains("/.git/")).sorted().forEach(p -> {
                Matcher m = Pattern.compile("(?m)^name:\\s*['\"]?([^'\"\\s]+)").matcher(read(p));
                if (m.find()) {
                    out.putIfAbsent(m.group(1), p.getParent());
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /**
     * {@code # Source: umbrella/charts/orders/templates/deployment.yaml} → the template in the repo: the deepest chart
     * the path names ({@code charts/<sub>/…} inside an umbrella chart), else the path as it is.
     */
    static Evidence sourceEvidence(Path root, Map<String, Path> charts, String source) {
        String[] parts = source.split("/");
        int chartAt = 0;
        for (int i = 0; i + 1 < parts.length; i++) {
            if (parts[i].equals("charts") && i + 1 < parts.length && charts.containsKey(parts[i + 1])) {
                chartAt = i + 1;
            }
        }
        Path chart = charts.get(parts[chartAt]);
        if (chart == null) {
            return Evidence.at(source, null);
        }
        Path file = chart.resolve(String.join("/", java.util.Arrays.asList(parts).subList(chartAt + 1, parts.length)));
        return Evidence.at(rel(root, Files.exists(file) ? file : chart.resolve("Chart.yaml")), null);
    }

    /** The first line of a values file (not a template) that names {@code name}: where people edit it. */
    static Evidence valuesLine(Path root, String name, Path skip) {
        Path skipped = skip == null ? null : skip.toAbsolutePath().normalize();
        Pattern exact = Pattern.compile("(^|[\\s:\"'\\-])" + Pattern.quote(name) + "($|[\\s\"',])");
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().matches(".*\\.(ya?ml|gotmpl)")
                    && !f.toString().contains("/templates/") && !f.toString().contains("/.git/")
                    && !f.getFileName().toString().equals("Chart.yaml")
                    && (skipped == null || !f.toAbsolutePath().normalize().startsWith(skipped))).sorted().toList()) {
                List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (exact.matcher(lines.get(i)).find()) {
                        return Evidence.at(rel(root, p), i + 1);
                    }
                }
            }
        } catch (IOException | UncheckedIOException e) {
            return null;
        }
        return null;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String rel(Path root, Path p) {
        return root.toAbsolutePath().normalize().relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

}
