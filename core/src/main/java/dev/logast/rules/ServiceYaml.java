package dev.logast.rules;

import dev.logast.SpringProperties;
import dev.logast.orders.Edit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** The service's base application.yaml/.yml: where YAML fixes go. */
public final class ServiceYaml {
    private ServiceYaml() {
    }

    /**
     * Where config fixes go, relative to the project root: the application module's application.yml/.yaml; a service
     * configured only through application.properties keeps its format (#47).
     */
    static String path(RuleContext ctx) {
        Path dir = dir(ctx);
        Path yml = Files.exists(dir.resolve("application.yaml")) ? dir.resolve("application.yaml") : dir.resolve("application.yml");
        Path props = dir.resolve("application.properties");
        Path target = !Files.exists(yml) && Files.exists(props) ? props : yml;
        return ctx.a.project.root().relativize(target).toString().replace('\\', '/');
    }

    private static Path dir(RuleContext ctx) {
        Path app = SpringProperties.appResources(ctx.a.project.root());
        return app != null ? app : ctx.a.project.root().resolve("src/main/resources");
    }

    /** Merges {@code snippet} (nested YAML) into the file: new keys are added, existing values replaced. */
    static void merge(RuleContext ctx, OrderBuilder b, String snippet) {
        merge(ctx, b, snippet, false);
    }

    /** @param overwrite the snippet's values replace existing ones (for fixes that change a setting) */
    static void merge(RuleContext ctx, OrderBuilder b, String snippet, boolean overwrite) {
        // .properties takes precedence over YAML in the same folder: a key set there must be changed there, or the fix
        // has no effect at runtime while the finding disappears
        Path props = dir(ctx).resolve("application.properties");
        String target = path(ctx);
        if (Files.exists(props)) {
            Map<String, String> flat = flatYaml(snippet);
            String text = SetupRules.read(props);
            boolean setThere = flat.keySet().stream().anyMatch(k -> java.util.regex.Pattern.compile(
                    "(?m)^\\s*" + java.util.regex.Pattern.quote(k) + "\\s*[=:]").matcher(baseDocument(text)).find());
            if (target.endsWith(".properties") || setThere) {
                StringBuilder lines = new StringBuilder();
                flat.forEach((k, v) -> lines.append(k).append('=').append(v).append('\n'));
                b.edit(Edit.propertiesMerge(ctx.a.project.root().relativize(props).toString().replace('\\', '/'),
                        lines.toString(), overwrite));
                return;
            }
        }
        b.edit(Edit.yamlMerge(target, snippet, overwrite));
    }

    /** The part of a .properties file before its first document separator (#--- or !---). */
    static String baseDocument(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^[#!]---\\s*$").matcher(text);
        return m.find() ? text.substring(0, m.start()) : text;
    }

    /** A nested YAML snippet as dotted keys, in order. */
    static Map<String, String> flatYaml(String snippet) {
        Map<String, String> out = new LinkedHashMap<>();
        Deque<Map.Entry<Integer, String>> path = new ArrayDeque<>();
        for (String l : snippet.lines().toList()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\s*)([^:#\\s][^:]*):\\s*(.*)$").matcher(l);
            if (!m.matches()) {
                continue;
            }
            int indent = m.group(1).length();
            while (!path.isEmpty() && path.peek().getKey() >= indent) {
                path.pop();
            }
            String key = m.group(2).strip();
            if (m.group(3).isBlank()) {
                path.push(Map.entry(indent, key));
            } else {
                List<String> parts = new ArrayList<>(path.stream().map(Map.Entry::getValue).toList());
                java.util.Collections.reverse(parts);
                parts.add(key);
                out.put(String.join(".", parts), m.group(3).strip());
            }
        }
        return out;
    }

    /**
     * Merges {@code key=value} lines into the base document of a .properties file: an existing key keeps its value
     * unless {@code overwrite}, a new one is added at the end of the base document.
     */
    public static String mergeProperties(String existing, String lines, boolean overwrite) {
        String base = baseDocument(existing);
        String rest = existing.substring(base.length());
        for (String l : lines.lines().filter(x -> x.contains("=")).toList()) {
            String key = l.substring(0, l.indexOf('=')).strip();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^(\\s*" + java.util.regex.Pattern.quote(key)
                    + "\\s*[=:]).*$").matcher(base);
            if (m.find()) {
                if (overwrite) {
                    base = base.substring(0, m.start()) + l + base.substring(m.end());
                }
            } else {
                base = (base.isEmpty() || base.endsWith("\n") ? base : base + "\n") + l + "\n";
            }
        }
        return base + rest;
    }

    /** True when any application*.yml/.properties mentions {@code text} (profiles and env-specific files included). */
    static boolean mentioned(RuleContext ctx, String text) {
        for (Path dir : SpringProperties.resourceDirs(ctx.a.project.root())) {
            try (var files = Files.list(dir)) {
                if (files.filter(p -> p.getFileName().toString().startsWith("application"))
                        .anyMatch(p -> SetupRules.read(p).contains(text))) {
                    return true;
                }
            } catch (java.io.IOException e) {
                return false;
            }
        }
        return false;
    }
}
