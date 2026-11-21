package dev.logast.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * #214: the well-known commands in a workflow step's {@code run:} or a script, and what they reach: a workflow
 * dispatched ({@code gh workflow run}, the REST API's {@code dispatches}), a repository cloned or pushed, a file written
 * ({@code yq -i}, {@code sed -i}), a Helm release installed, manifests applied. Anything else is not read. Variables are
 * kept as written ({@code ${SERVICE}}, {@code ${{ inputs.service }}}).
 */
final class ShellCommands {
    /** What a command does: {@code kind} (dispatches, clones, pushes, writes, installs, applies), its target, details. */
    record Command(String kind, String target, Map<String, Object> attrs, int line) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    /** {@code https://[user[:token]@]github.com/o/r[.git]}, {@code git@github.com:o/r[.git]}, any host. */
    private static final Pattern GIT_URL = Pattern.compile("^(?:https?://(?:[^@/]+@)?[^/]+/|git@[^:]+:|ssh://(?:[^@/]+@)?[^/]+/)([^/\\s]+/[^/\\s]+?)(?:\\.git)?/?$");
    private static final Pattern API_DISPATCH = Pattern.compile("repos/([^/\\s\"']+/[^/\\s\"']+)/(?:actions/workflows/([^/\\s\"']+)/)?dispatches");
    private static final Pattern GH_REPO = Pattern.compile("^([^/]+)/([^/]+)$");

    private ShellCommands() {
    }

    /**
     * @param text a {@code run:} block or a script's text
     * @param repo the repository it runs in ({@code owner/name}), for a dispatch without {@code -R}
     */
    static List<Command> read(String text, String repo) {
        List<Command> out = new ArrayList<>();
        List<String> lines = logicalLines(text);
        Map<String, String> clonedInto = new LinkedHashMap<>();   // a clone's folder → the repo
        Map<String, String> vars = new LinkedHashMap<>();   // the script's own assignments, expanded where used
        String cwdRepo = null;
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            int lineNo = lineOf(text, raw);
            for (List<String> t0 : commands(raw)) {
                List<String> t = new ArrayList<>(t0);
                if (!t.isEmpty() && t.getFirst().equals("export")) {
                    t.removeFirst();
                }
                if (!t.isEmpty() && t.stream().allMatch(w -> ASSIGN.matcher(w).matches())) {
                    for (String w : t) {
                        Matcher a = ASSIGN.matcher(w);
                        if (a.matches()) {
                            vars.put(a.group(1), expand(a.group(2), vars));
                        }
                    }
                    continue;
                }
                t.replaceAll(w -> expand(w, vars));
                String part = String.join(" ", t);
                String cmd = t.getFirst().replaceFirst("^.*/", "");
                if (cmd.equals("cd") && t.size() > 1) {
                    String dir = t.get(1).replaceFirst("/+$", "");
                    cwdRepo = clonedInto.getOrDefault(dir, dir.equals("..") || dir.equals("-") ? null : cwdRepo);
                } else if (cmd.equals("git") && t.size() > 1 && t.get(1).equals("clone")) {
                    List<String> args = positional(t.subList(2, t.size()), List.of("-b", "--branch", "--depth", "-c", "--config", "--origin", "-o"));
                    if (!args.isEmpty()) {
                        String target = repoOf(args.getFirst());
                        if (target != null) {
                            String dir = args.size() > 1 ? args.get(1).replaceFirst("/+$", "") : target.substring(target.indexOf('/') + 1);
                            clonedInto.put(dir, target);
                            out.add(new Command("clones", "repo:" + target, Map.of(), lineNo));
                        }
                    }
                } else if (cmd.equals("git") && t.size() > 1 && t.get(1).equals("push")) {
                    String target = cwdRepo != null ? cwdRepo : clonedInto.size() == 1 ? clonedInto.values().iterator().next() : null;
                    if (target != null) {
                        out.add(new Command("pushes", "repo:" + target, Map.of(), lineNo));
                    }
                } else if (cmd.equals("yq")) {
                    yq(t, cwdRepo, clonedInto, lineNo, out);
                } else if (cmd.equals("sed") && t.stream().anyMatch(a -> a.equals("-i") || a.startsWith("-i") && !a.startsWith("-i=") || a.equals("--in-place"))) {
                    List<String> args = positional(t.subList(1, t.size()), List.of("-e", "--expression"));
                    if (args.size() >= 2) {
                        Map<String, Object> a = new LinkedHashMap<>();
                        a.put("via", "sed");
                        a.put("expression", args.getFirst());
                        for (String f : args.subList(1, args.size())) {
                            out.add(new Command("writes", file(f, cwdRepo, clonedInto, repo), a, lineNo));
                        }
                    }
                } else if (cmd.equals("gh") && t.size() > 2 && t.get(1).equals("workflow") && t.get(2).equals("run") && t.size() > 3) {
                    ghWorkflowRun(t, repo, lineNo, out);
                } else if (cmd.equals("curl") || cmd.equals("gh") && t.size() > 1 && t.get(1).equals("api")) {
                    apiDispatch(part, t, lineNo, out);
                } else if (cmd.equals("helm") && t.size() > 3 && (t.get(1).equals("upgrade") || t.get(1).equals("install"))) {
                    helm(t, lineNo, out);
                } else if (cmd.equals("kubectl") && t.size() > 2 && t.get(1).equals("apply")) {
                    for (int k = 2; k < t.size() - 1; k++) {
                        if (t.get(k).equals("-f") || t.get(k).equals("--filename") || t.get(k).equals("-k")) {
                            out.add(new Command("applies", file(t.get(k + 1), cwdRepo, clonedInto, repo), Map.of(), lineNo));
                        }
                    }
                }
            }
        }
        return out;
    }

    /** {@code yq -i '.a.b = "x"' file} (also {@code yq e -i}, {@code yq eval -i}): the file and the path it sets. */
    private static void yq(List<String> t, String cwdRepo, Map<String, String> clonedInto, int lineNo, List<Command> out) {
        if (t.stream().noneMatch(a -> a.equals("-i") || a.equals("--inplace"))) {
            return;
        }
        List<String> args = new ArrayList<>(positional(t.subList(1, t.size()), List.of("--arg", "-o", "--output-format", "-p", "--input-format")));
        if (!args.isEmpty() && (args.getFirst().equals("e") || args.getFirst().equals("eval"))) {
            args.removeFirst();
        }
        if (args.size() < 2) {
            return;
        }
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("via", "yq");
        String expr = args.getFirst();
        a.put("expression", expr);
        Matcher m = Pattern.compile("^\\s*(\\.[^=|]+?)\\s*\\|?=").matcher(expr);
        if (m.find()) {
            a.put("key", m.group(1).strip());
        }
        for (String f : args.subList(1, args.size())) {
            out.add(new Command("writes", file(f, cwdRepo, clonedInto, null), a, lineNo));
        }
    }

    private static void ghWorkflowRun(List<String> t, String repo, int lineNo, List<Command> out) {
        String wf = t.get(3);
        String target = repo;
        Map<String, Object> inputs = new LinkedHashMap<>();
        String ref = null;
        for (int k = 4; k < t.size(); k++) {
            String a = t.get(k);
            String v = k + 1 < t.size() ? t.get(k + 1) : null;
            if ((a.equals("-R") || a.equals("--repo")) && v != null) {
                target = v;
                k++;
            } else if (a.equals("--ref") && v != null) {
                ref = v;
                k++;
            } else if ((a.equals("-f") || a.equals("-F") || a.equals("--field") || a.equals("--raw-field")) && v != null && v.contains("=")) {
                inputs.put(v.substring(0, v.indexOf('=')), dev.logast.facts.Secrets.clean(v.substring(0, v.indexOf('=')), v.substring(v.indexOf('=') + 1)));
                k++;
            }
        }
        if (target == null) {
            return;
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("via", "gh workflow run");
        if (!inputs.isEmpty()) {
            attrs.put("inputs", inputs);
        }
        if (ref != null) {
            attrs.put("ref", ref);
        }
        out.add(new Command("dispatches", workflow(target, wf), attrs, lineNo));
    }

    /** The REST API: {@code repos/o/r/actions/workflows/<wf>/dispatches} (inputs) or {@code repos/o/r/dispatches} (an event type). */
    private static void apiDispatch(String text, List<String> t, int lineNo, List<Command> out) {
        Matcher m = API_DISPATCH.matcher(text);
        if (!m.find()) {
            return;
        }
        String target = m.group(1);
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("via", t.getFirst().replaceFirst("^.*/", "").equals("gh") ? "gh api" : "curl");
        JsonNode body = null;
        Map<String, String> fields = new LinkedHashMap<>();
        for (int k = 1; k < t.size() - 1; k++) {
            String a = t.get(k);
            if (a.equals("-d") || a.equals("--data") || a.equals("--data-raw") || a.equals("--json")) {
                try {
                    body = JSON.readTree(t.get(k + 1));
                } catch (Exception e) {
                    body = null;   // a body built from variables: not readable
                }
            } else if ((a.equals("-f") || a.equals("-F") || a.equals("--raw-field") || a.equals("--field")) && t.get(k + 1).contains("=")) {
                String v = t.get(k + 1);
                fields.put(v.substring(0, v.indexOf('=')), v.substring(v.indexOf('=') + 1));
            }
        }
        if (m.group(2) != null) {
            Map<String, Object> inputs = new LinkedHashMap<>();
            if (body != null) {
                body.path("inputs").fields().forEachRemaining(e -> inputs.put(e.getKey(), dev.logast.facts.Secrets.clean(e.getKey(), e.getValue().asText())));
            }
            fields.forEach((k, v) -> {
                if (k.startsWith("inputs[")) {
                    String name = k.substring(7, k.length() - 1);
                    inputs.put(name, dev.logast.facts.Secrets.clean(name, v));
                }
            });
            if (!inputs.isEmpty()) {
                attrs.put("inputs", inputs);
            }
            out.add(new Command("dispatches", workflow(target, m.group(2)), attrs, lineNo));
        } else {
            String type = body != null ? body.path("event_type").asText(null) : fields.get("event_type");
            if (type == null) {
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            if (body != null) {
                body.path("client_payload").fields().forEachRemaining(e -> {
                    if (e.getValue().isValueNode()) {
                        payload.put(e.getKey(), dev.logast.facts.Secrets.clean(e.getKey(), e.getValue().asText()));
                    }
                });
            }
            if (!payload.isEmpty()) {
                attrs.put("payload", payload);
            }
            out.add(new Command("dispatches", event(target, type), attrs, lineNo));
        }
    }

    private static void helm(List<String> t, int lineNo, List<Command> out) {
        List<String> args = positional(t.subList(2, t.size()), List.of("--set", "--set-string", "-f", "--values", "-n", "--namespace",
                "--version", "--timeout", "--kube-context", "--repo"));
        if (args.size() < 2) {
            return;
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("chart", args.get(1));
        Map<String, Object> sets = new LinkedHashMap<>();
        List<String> values = new ArrayList<>();
        for (int k = 2; k < t.size() - 1; k++) {
            String a = t.get(k);
            String v = t.get(k + 1);
            if ((a.equals("--set") || a.equals("--set-string")) && v.contains("=")) {
                for (String kv : v.split(",")) {
                    if (kv.contains("=")) {
                        String key = kv.substring(0, kv.indexOf('='));
                        sets.put(key, dev.logast.facts.Secrets.clean(key, kv.substring(kv.indexOf('=') + 1)));
                    }
                }
            } else if (a.equals("-f") || a.equals("--values")) {
                values.add(v);
            } else if (a.equals("-n") || a.equals("--namespace")) {
                attrs.put("namespace", v);
            }
        }
        if (!sets.isEmpty()) {
            attrs.put("set", sets);
        }
        if (!values.isEmpty()) {
            attrs.put("values", values);
        }
        out.add(new Command("installs", "helm-release:" + args.getFirst(), attrs, lineNo));
    }

    static String workflow(String repo, String wf) {
        return "workflow:" + repo + "/.github/workflows/" + wf;
    }

    /** #214: a {@code repository_dispatch} event of a repository: its senders and the workflows it triggers meet here. */
    static String event(String repo, String type) {
        return "event:github:" + repo + ":" + type;
    }

    /** {@code owner/name} of a git URL, else of {@code owner/name} itself (a {@code gh -R} value); null when neither. */
    static String repoOf(String url) {
        Matcher m = GIT_URL.matcher(url);
        if (m.matches()) {
            return m.group(1);
        }
        return GH_REPO.matcher(url).matches() && !url.startsWith(".") ? url : null;
    }

    /** A file a command reaches: in the repo it was cloned into (by its folder, or the current one), else this repo's. */
    private static String file(String path, String cwdRepo, Map<String, String> clonedInto, String repo) {
        String p = path.replaceFirst("^\\./", "");
        for (var e : clonedInto.entrySet()) {
            if (p.startsWith(e.getKey() + "/")) {
                return "file:" + e.getValue() + "/" + p.substring(e.getKey().length() + 1);
            }
        }
        if (cwdRepo != null) {
            return "file:" + cwdRepo + "/" + p;
        }
        return repo == null ? "file:" + p : "file:" + repo + "/" + p;
    }

    /** The arguments that are not options (an option in {@code withValue} takes the next one). */
    private static List<String> positional(List<String> args, List<String> withValue) {
        List<String> out = new ArrayList<>();
        for (int k = 0; k < args.size(); k++) {
            String a = args.get(k);
            if (withValue.contains(a)) {
                k++;
            } else if (!a.startsWith("-") || a.equals("-")) {
                out.add(a);
            }
        }
        return out;
    }

    /** Lines with their {@code \}-continuations joined, comments dropped. */
    private static List<String> logicalLines(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String l : text.split("\\R")) {
            String s = l.strip();
            if (cur.isEmpty() && s.startsWith("#")) {
                continue;
            }
            if (s.endsWith("\\")) {
                cur.append(s, 0, s.length() - 1).append(' ');
            } else {
                cur.append(s);
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (!cur.isEmpty()) {
            out.add(cur.toString());
        }
        return out;
    }

    private static int lineOf(String text, String logical) {
        String head = logical.length() > 40 ? logical.substring(0, 40) : logical;
        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            if (!head.isBlank() && lines[i].contains(head.strip())) {
                return i + 1;
            }
        }
        return 1;
    }

    private static final String OP = "\u0000";
    private static final Pattern ASSIGN = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)=(.*)$");
    /** {@code $NAME}, {@code ${NAME}}, {@code $1}; not {@code ${{ … }}} (GitHub's). */
    static final Pattern VAR = Pattern.compile("\\$(?:\\{([A-Za-z_][A-Za-z0-9_]*|[0-9])}|([A-Za-z_][A-Za-z0-9_]*|[0-9]))");

    /** The variables of {@code vars} replaced in {@code s}; others kept as written. */
    static String expand(String s, Map<String, String> vars) {
        if (s.indexOf('$') < 0 || vars.isEmpty()) {
            return s;
        }
        Matcher m = VAR.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(2);
            String v = vars.get(name);
            m.appendReplacement(sb, Matcher.quoteReplacement(v != null ? v : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** A line's commands: its words split at {@code &&}, {@code ||}, {@code ;} and {@code |} outside quotes. */
    static List<List<String>> commands(String line) {
        List<List<String>> out = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        for (String w : tokens(line)) {
            if (w.equals(OP)) {
                if (!cur.isEmpty()) {
                    out.add(cur);
                }
                cur = new ArrayList<>();
            } else {
                cur.add(w);
            }
        }
        if (!cur.isEmpty()) {
            out.add(cur);
        }
        return out;
    }

    /** Shell words: quotes ('…', "…") kept together and removed; an operator outside quotes is {@link #OP}. */
    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean any = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (s.startsWith("${{", i) && s.indexOf("}}", i) > 0) {   // GitHub substitutes it before the shell runs: one word
                int end = s.indexOf("}}", i) + 2;
                cur.append(s, i, end);
                i = end - 1;
                continue;
            }
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else if (c == '\\' && quote == '"' && i + 1 < s.length() && (s.charAt(i + 1) == '"' || s.charAt(i + 1) == '\\')) {
                    cur.append(s.charAt(++i));
                } else {
                    cur.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                any = true;
            } else if (c == ';' || c == '|' || c == '&' && i + 1 < s.length() && s.charAt(i + 1) == '&') {
                if (any || !cur.isEmpty()) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    any = false;
                }
                out.add(OP);
                if ((c == '|' || c == '&') && i + 1 < s.length() && s.charAt(i + 1) == c) {
                    i++;
                }
            } else if (Character.isWhitespace(c)) {
                if (any || !cur.isEmpty()) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    any = false;
                }
            } else {
                cur.append(c);
            }
        }
        if (any || !cur.isEmpty()) {
            out.add(cur.toString());
        }
        return out;
    }
}
