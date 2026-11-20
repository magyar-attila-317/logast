package dev.logast.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.logast.facts.Evidence;
import dev.logast.facts.FactSink;
import dev.logast.facts.Node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * #173: self-hosted GitHub runners an Ansible inventory sets up: every host whose variables (inline, group_vars,
 * host_vars) configure runner labels is {@code runner-host:<host>}, with its groups, labels and runner count.
 */
public final class AnsibleRunners {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern LABELS_KEY = Pattern.compile("(?i).*runner.*labels?|labels");
    private static final Pattern COUNT_KEY = Pattern.compile("(?i)runner_?count|runners_?per_?host|.*runner.*count");
    private static final Pattern INI_GROUP = Pattern.compile("^\\[([^\\]:]+)(?::(children|vars))?]\\s*$");

    private AnsibleRunners() {
    }

    /** Inventory files: under {@code inventory/} or {@code inventories/}, or named {@code hosts}/{@code inventory}(.ini/.yml). */
    static List<Path> inventories(Path root) {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).filter(p -> !p.toString().contains("/.git/")).filter(p -> {
                String rel = root.relativize(p).toString().replace('\\', '/');
                String name = p.getFileName().toString();
                boolean inInventoryDir = rel.matches("(.*/)?inventor(y|ies)/.*") && !rel.contains("group_vars/") && !rel.contains("host_vars/");
                return (inInventoryDir || name.matches("(hosts|inventory)(\\.(ini|ya?ml))?")) && name.matches("[^.]+(\\.(ini|ya?ml))?");
            }).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public static boolean applies(Path root) {
        return !inventories(root).isEmpty();
    }

    /** One host: its groups, inline variables and where it is declared. */
    record Host(String name, Set<String> groups, Map<String, Object> vars, Evidence at) {
    }

    public static void extract(Path root, FactSink out) {
        Map<String, Host> hosts = new LinkedHashMap<>();
        Map<String, Set<String>> children = new LinkedHashMap<>();
        Map<String, Map<String, Object>> groupVars = new LinkedHashMap<>();
        for (Path inv : inventories(root)) {
            String rel = root.relativize(inv).toString().replace('\\', '/');
            String text;
            try {
                text = Files.readString(inv);
            } catch (IOException e) {
                continue;
            }
            if (inv.getFileName().toString().matches(".*\\.ya?ml")) {
                yamlInventory(text, rel, hosts, children, groupVars);
            } else {
                iniInventory(text, rel, hosts, children, groupVars);
            }
            varsDirs(inv.getParent(), groupVars, hosts);
        }
        varsDirs(root, groupVars, hosts);
        for (Host h : hosts.values()) {
            Map<String, Object> vars = new LinkedHashMap<>();
            for (String g : withParents(h.groups(), children)) {
                vars.putAll(groupVars.getOrDefault(g, Map.of()));
            }
            vars.putAll(groupVars.getOrDefault("all", Map.of()));
            vars.putAll(h.vars());
            List<String> labels = new ArrayList<>();
            Object count = null;
            for (var e : vars.entrySet()) {
                if (LABELS_KEY.matcher(e.getKey()).matches() && (e.getKey().toLowerCase().contains("runner") || vars.keySet().stream()
                        .anyMatch(k -> k.toLowerCase().contains("runner")))) {
                    labels.addAll(list(e.getValue()));
                } else if (COUNT_KEY.matcher(e.getKey()).matches()) {
                    count = e.getValue();
                }
            }
            if (labels.isEmpty()) {
                continue;   // a host that runs no runner
            }
            Map<String, Object> attrs = new TreeMap<>();
            attrs.put("groups", List.copyOf(h.groups()));
            attrs.put("labels", labels.stream().distinct().toList());
            if (count != null) {
                attrs.put("count", String.valueOf(count));
            }
            out.node(new Node("runner-host:" + h.name(), "runner-host", null, attrs, h.at()));
        }
    }

    private static Set<String> withParents(Set<String> groups, Map<String, Set<String>> children) {
        Set<String> out = new LinkedHashSet<>();
        List<String> todo = new ArrayList<>(groups);
        while (!todo.isEmpty()) {
            String g = todo.removeLast();
            if (out.add(g)) {
                children.forEach((parent, kids) -> {
                    if (kids.contains(g)) {
                        todo.add(parent);
                    }
                });
            }
        }
        // parents first, so a child group's variables win
        List<String> ordered = new ArrayList<>(out);
        java.util.Collections.reverse(ordered);
        return new LinkedHashSet<>(ordered);
    }

    private static void iniInventory(String text, String rel, Map<String, Host> hosts, Map<String, Set<String>> children,
                                     Map<String, Map<String, Object>> groupVars) {
        String group = "ungrouped";
        String section = "hosts";
        List<String> lines = text.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                continue;
            }
            Matcher g = INI_GROUP.matcher(line);
            if (g.matches()) {
                group = g.group(1);
                section = g.group(2) == null ? "hosts" : g.group(2);
                continue;
            }
            String[] parts = line.split("\\s+");
            switch (section) {
                case "children" -> children.computeIfAbsent(group, k -> new LinkedHashSet<>()).add(parts[0]);
                case "vars" -> {
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        groupVars.computeIfAbsent(group, k -> new LinkedHashMap<>()).put(line.substring(0, eq).strip(), unquote(line.substring(eq + 1).strip()));
                    }
                }
                default -> {
                    Host h = hosts.computeIfAbsent(parts[0], n -> new Host(n, new LinkedHashSet<>(), new LinkedHashMap<>(), Evidence.at(rel, null)));
                    h.groups().add(group);
                    for (int p = 1; p < parts.length; p++) {
                        int eq = parts[p].indexOf('=');
                        if (eq > 0) {
                            h.vars().put(parts[p].substring(0, eq), unquote(parts[p].substring(eq + 1)));
                        }
                    }
                    if (h.at().line() == null) {
                        hosts.put(h.name(), new Host(h.name(), h.groups(), h.vars(), Evidence.at(rel, i + 1)));
                    }
                }
            }
        }
    }

    private static void yamlInventory(String text, String rel, Map<String, Host> hosts, Map<String, Set<String>> children,
                                      Map<String, Map<String, Object>> groupVars) {
        JsonNode root;
        try {
            root = YAML.readTree(text);
        } catch (IOException e) {
            return;
        }
        if (root == null) {
            return;
        }
        List<String> lines = text.lines().toList();
        for (Iterator<Map.Entry<String, JsonNode>> it = root.fields(); it.hasNext(); ) {
            var e = it.next();
            yamlGroup(e.getKey(), e.getValue(), rel, lines, hosts, children, groupVars);
        }
    }

    private static void yamlGroup(String group, JsonNode g, String rel, List<String> lines, Map<String, Host> hosts,
                                  Map<String, Set<String>> children, Map<String, Map<String, Object>> groupVars) {
        g.path("hosts").fields().forEachRemaining(h -> {
            Host host = hosts.computeIfAbsent(h.getKey(), n -> new Host(n, new LinkedHashSet<>(), new LinkedHashMap<>(), Evidence.at(rel, line(lines, n))));
            host.groups().add(group);
            h.getValue().fields().forEachRemaining(v -> host.vars().put(v.getKey(), YAML.convertValue(v.getValue(), Object.class)));
        });
        g.path("vars").fields().forEachRemaining(v -> groupVars.computeIfAbsent(group, k -> new LinkedHashMap<>())
                .put(v.getKey(), YAML.convertValue(v.getValue(), Object.class)));
        g.path("children").fields().forEachRemaining(c -> {
            children.computeIfAbsent(group, k -> new LinkedHashSet<>()).add(c.getKey());
            yamlGroup(c.getKey(), c.getValue(), rel, lines, hosts, children, groupVars);
        });
    }

    /** group_vars/&lt;group&gt;(.yml|/…) and host_vars/&lt;host&gt;(.yml|/…) next to an inventory. */
    private static void varsDirs(Path dir, Map<String, Map<String, Object>> groupVars, Map<String, Host> hosts) {
        for (String kind : List.of("group_vars", "host_vars")) {
            Path d = dir.resolve(kind);
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (Stream<Path> s = Files.walk(d)) {
                for (Path f : s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().matches(".*\\.ya?ml|[^.]+")).sorted().toList()) {
                    Path relToDir = d.relativize(f);
                    String owner = relToDir.getName(0).toString().replaceFirst("\\.ya?ml$", "");
                    JsonNode vars = YAML.readTree(f.toFile());
                    if (vars == null || !vars.isObject()) {
                        continue;
                    }
                    Map<String, Object> m = YAML.convertValue(vars, Map.class);
                    if (kind.equals("group_vars")) {
                        groupVars.computeIfAbsent(owner, k -> new LinkedHashMap<>()).putAll(m);
                    } else if (hosts.containsKey(owner)) {
                        hosts.get(owner).vars().putAll(m);
                    }
                }
            } catch (IOException | RuntimeException e) {
                // unreadable vars: the inventory alone
            }
        }
    }

    private static List<String> list(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            l.forEach(x -> out.add(String.valueOf(x).strip()));
        } else if (v != null) {
            for (String s : String.valueOf(v).replaceAll("[\\[\\]\"']", "").split(",")) {
                if (!s.isBlank()) {
                    out.add(s.strip());
                }
            }
        }
        return out;
    }

    private static Integer line(List<String> lines, String host) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).strip().startsWith(host + ":")) {
                return i + 1;
            }
        }
        return null;
    }

    private static String unquote(String s) {
        return s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'")) ? s.substring(1, s.length() - 1) : s;
    }
}
