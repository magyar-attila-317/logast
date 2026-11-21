package dev.logast.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.logast.facts.Edge;
import dev.logast.facts.Evidence;
import dev.logast.facts.FactSink;
import dev.logast.facts.Node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * #167: a repo's GitHub Actions workflows: their triggers, whether they are reusable (with inputs and secrets), the
 * reusable workflows and actions they use and at which ref, their jobs' runner labels, and the repo's scripts their
 * steps run.
 */
public final class WorkflowExtractor implements Extractor {
    public static final String ID = "github-actions";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    /** {@code owner/repo/.github/workflows/file.yml@ref}, or {@code ./.github/workflows/file.yml} in the same repo. */
    private static final Pattern REUSABLE = Pattern.compile("^([^/@]+/[^/@]+)/(\\.github/workflows/[^@]+)@(.+)$");
    private static final Pattern ACTION = Pattern.compile("^([^/@]+/[^@]+)@(.+)$");
    /** A step's run line that runs a file of the repo: {@code ./scripts/x.sh}, {@code bash ci/y.sh}, {@code sh x.sh}. */
    private static final Pattern SCRIPT = Pattern.compile("(?:^|[\\s;&|(])(?:(?:bash|sh|zsh|source|\\.)\\s+)?(\\.?/?[\\w./-]+\\.(?:sh|bash|py|ps1))\\b");

    private final String repo;
    private final List<String> problems = new ArrayList<>();

    /** @param repo the repository's {@code owner/name} or name, as other repos' {@code uses:} name it */
    public WorkflowExtractor(String repo) {
        this.repo = repo;
    }

    @Override
    public String id() {
        return ID;
    }

    public List<String> problems() {
        return List.copyOf(problems);
    }

    public static boolean applies(Path root) {
        return !files(root).isEmpty();
    }

    static List<Path> files(Path root) {
        Path dir = root.resolve(".github/workflows");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().matches(".*\\.ya?ml")).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    @Override
    public void extract(Path root, FactSink out) {
        for (Path f : files(root)) {
            String rel = ".github/workflows/" + f.getFileName();
            JsonNode wf;
            List<String> lines;
            try {
                lines = Files.readAllLines(f);
                wf = YAML.readTree(f.toFile());
            } catch (IOException e) {
                problems.add(rel + ": " + e.getMessage());
                continue;
            }
            if (wf == null || !wf.isObject()) {
                continue;
            }
            String id = "workflow:" + repo + "/" + rel;
            Map<String, Object> attrs = new TreeMap<>();
            attrs.put("name", wf.path("name").asText(f.getFileName().toString()));
            // YAML reads a bare `on:` key as the boolean true
            JsonNode on = wf.has("on") ? wf.get("on") : wf.get("true");
            List<String> triggers = new ArrayList<>();
            if (on != null) {
                if (on.isTextual()) {
                    triggers.add(on.asText());
                } else if (on.isArray()) {
                    on.forEach(t -> triggers.add(t.asText()));
                } else {
                    on.fieldNames().forEachRemaining(triggers::add);
                }
                JsonNode call = on.path("workflow_call");
                if (triggers.contains("workflow_call")) {
                    attrs.put("reusable", true);
                    List<String> inputs = new ArrayList<>();
                    call.path("inputs").fieldNames().forEachRemaining(inputs::add);
                    List<String> secrets = new ArrayList<>();
                    call.path("secrets").fieldNames().forEachRemaining(secrets::add);
                    if (!inputs.isEmpty()) {
                        attrs.put("inputs", inputs);
                    }
                    if (!secrets.isEmpty()) {
                        attrs.put("secrets", secrets);
                    }
                }
                if (on.isObject()) {   // #214: what a dispatch reaches
                    JsonNode rd = on.path("repository_dispatch");
                    for (JsonNode type : rd.path("types").isArray() ? rd.path("types") : YAML.createArrayNode()) {
                        out.edge(Edge.extracted(ShellCommands.event(repo, type.asText()), "triggers", id, null, Evidence.at(rel, line(lines, type.asText()))));
                    }
                    List<String> manual = new ArrayList<>();
                    on.path("workflow_dispatch").path("inputs").fieldNames().forEachRemaining(manual::add);
                    if (!manual.isEmpty()) {
                        attrs.put("dispatchInputs", manual);
                    }
                }
                JsonNode cron = on.path("schedule");
                if (cron.isArray()) {
                    List<String> crons = new ArrayList<>();
                    cron.forEach(c -> crons.add(c.path("cron").asText()));
                    attrs.put("schedule", crons.size() == 1 ? crons.getFirst() : String.join(" | ", crons));
                    attrs.put("scheduleFormat", "unix");
                }
            }
            attrs.put("triggers", triggers);
            Set<String> labels = new LinkedHashSet<>();
            Set<String> scripts = new LinkedHashSet<>();
            Evidence top = Evidence.at(rel, 1);
            for (Iterator<Map.Entry<String, JsonNode>> it = wf.path("jobs").fields(); it.hasNext(); ) {
                var job = it.next();
                JsonNode j = job.getValue();
                JsonNode runsOn = j.path("runs-on");
                if (runsOn.isTextual()) {
                    labels.add(runsOn.asText());
                } else if (runsOn.isArray()) {
                    runsOn.forEach(l -> labels.add(l.asText()));
                } else if (runsOn.has("labels")) {
                    runsOn.path("labels").forEach(l -> labels.add(l.asText()));
                }
                String uses = j.path("uses").asText("");
                if (!uses.isBlank()) {
                    Evidence at = Evidence.at(rel, line(lines, uses));
                    Matcher m = REUSABLE.matcher(uses);
                    Map<String, Object> ca = new TreeMap<>();
                    ca.put("job", job.getKey());
                    Map<String, Object> with = values(j.path("with"));   // #214: what the caller passes
                    if (!with.isEmpty()) {
                        ca.put("with", with);
                    }
                    if (m.matches()) {
                        ca.put("ref", m.group(3));
                        out.edge(Edge.extracted(id, "calls", "workflow:" + m.group(1) + "/" + m.group(2), ca, at));
                    } else if (uses.startsWith("./")) {
                        out.edge(Edge.extracted(id, "calls", "workflow:" + repo + "/" + uses.substring(2), ca, at));
                    }
                }
                Map<String, Object> jobEnv = new TreeMap<>(values(wf.path("env")));
                jobEnv.putAll(values(j.path("env")));
                for (JsonNode step : j.path("steps")) {
                    String su = step.path("uses").asText("");
                    if (!su.isBlank() && !su.startsWith("./") && !su.startsWith("docker://")) {
                        Matcher m = ACTION.matcher(su);
                        if (m.matches()) {
                            out.edge(Edge.extracted(id, "uses", "action:" + m.group(1), Map.of("ref", m.group(2)), Evidence.at(rel, line(lines, su))));
                            if (m.group(1).endsWith("/repository-dispatch")) {   // #214: a dispatch action
                                dispatchAction(id, step.path("with"), job.getKey(), Evidence.at(rel, line(lines, su)), out);
                            }
                        }
                    }
                    String run = step.path("run").asText("");
                    Map<String, Object> env = new TreeMap<>(jobEnv);
                    env.putAll(values(step.path("env")));
                    Matcher sm = SCRIPT.matcher(run);
                    while (sm.find()) {
                        String path = sm.group(1).replaceFirst("^\\./", "");
                        if (Files.isRegularFile(root.resolve(path)) && scripts.add(job.getKey() + " " + path)) {
                            Map<String, Object> ra = new TreeMap<>();
                            ra.put("job", job.getKey());
                            List<String> args = argsAfter(run, sm.group(1));
                            if (!args.isEmpty()) {
                                ra.put("args", args);
                            }
                            if (!env.isEmpty()) {
                                ra.put("env", env);
                            }
                            out.edge(Edge.extracted(id, "runs", "script:" + repo + "/" + path, ra, Evidence.at(rel, line(lines, sm.group(1)))));
                            script(root, path, out);
                        }
                    }
                    int base = line(lines, run.lines().findFirst().orElse("")) == null ? 1 : line(lines, run.lines().findFirst().orElse(""));
                    for (ShellCommands.Command c : ShellCommands.read(run, repo)) {
                        Map<String, Object> ca = new TreeMap<>(c.attrs());
                        ca.put("job", job.getKey());
                        out.edge(Edge.extracted(id, c.kind(), c.target(), ca, Evidence.at(rel, base + c.line() - 1)));
                    }
                }
            }
            if (!labels.isEmpty()) {
                attrs.put("runsOn", List.copyOf(labels));
            }
            out.node(new Node(id, "workflow", null, attrs, top));
        }
    }

    private final Set<String> scriptsRead = new LinkedHashSet<>();

    /** #214: what a script of the repo does with the well-known commands (once per script). */
    private void script(Path root, String path, FactSink out) {
        if (!scriptsRead.add(path)) {
            return;
        }
        String text;
        try {
            text = Files.readString(root.resolve(path));
        } catch (IOException e) {
            problems.add(path + ": " + e.getMessage());
            return;
        }
        for (ShellCommands.Command c : ShellCommands.read(text, repo)) {
            out.edge(Edge.extracted("script:" + repo + "/" + path, c.kind(), c.target(), c.attrs(), Evidence.at(path, c.line())));
        }
    }

    /** #214: {@code peter-evans/repository-dispatch}-style: {@code repository}, {@code event-type}, {@code client-payload}. */
    private void dispatchAction(String id, JsonNode with, String job, Evidence at, FactSink out) {
        String type = with.path("event-type").asText("");
        if (type.isBlank()) {
            return;
        }
        String target = with.path("repository").asText(repo);
        Map<String, Object> attrs = new TreeMap<>();
        attrs.put("job", job);
        attrs.put("via", "repository-dispatch action");
        String payload = with.path("client-payload").asText("");
        if (!payload.isBlank()) {
            try {
                Map<String, Object> p = new TreeMap<>();
                YAML.readTree(payload).fields().forEachRemaining(e -> {
                    if (e.getValue().isValueNode()) {
                        p.put(e.getKey(), dev.logast.facts.Secrets.clean(e.getKey(), e.getValue().asText()));
                    }
                });
                if (!p.isEmpty()) {
                    attrs.put("payload", p);
                }
            } catch (IOException | RuntimeException e) {
                attrs.put("payload", dev.logast.facts.Secrets.clean("client-payload", payload));
            }
        }
        out.edge(Edge.extracted(id, "dispatches", ShellCommands.event(target, type), attrs, at));
    }

    /** A mapping's scalar values as written ({@code ${{ inputs.x }}} kept), secrets cleaned. */
    private static Map<String, Object> values(JsonNode n) {
        Map<String, Object> out = new TreeMap<>();
        n.fields().forEachRemaining(e -> {
            if (e.getValue().isValueNode() && !e.getValue().isNull()) {
                out.put(e.getKey(), dev.logast.facts.Secrets.clean(e.getKey(), e.getValue().asText()));
            }
        });
        return out;
    }

    /** The words after a script's path on the command that runs it. */
    private static List<String> argsAfter(String run, String path) {
        for (String l : run.split("\\R")) {
            for (List<String> cmd : ShellCommands.commands(l)) {
                for (int i = 0; i < cmd.size(); i++) {
                    if (cmd.get(i).equals(path)) {
                        return List.copyOf(cmd.subList(i + 1, cmd.size()));
                    }
                }
            }
        }
        return List.of();
    }

    private static Integer line(List<String> lines, String text) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) {
                return i + 1;
            }
        }
        return null;
    }
}
