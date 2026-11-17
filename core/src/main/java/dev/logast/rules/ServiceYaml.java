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
            boolean setThere = flat.keySet().stream().anyMatch(k -> setsKey(text, k));
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

    private static final java.util.regex.Pattern PROPERTIES_SEPARATOR = java.util.regex.Pattern.compile("(?m)^[#!]---[ \\t]*\\R?");

    /**
     * The documents of a .properties file, split at {@code #---} / {@code !---} lines. Even indexes are documents, odd
     * ones the separator lines between them, so joining the list gives the text back unchanged.
     */
    static List<String> propertiesParts(String text) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = PROPERTIES_SEPARATOR.matcher(text);
        int at = 0;
        while (m.find()) {
            out.add(text.substring(at, m.start()));
            out.add(m.group());
            at = m.end();
        }
        out.add(text.substring(at));
        return out;
    }

    /** A document without {@code spring.config.activate.*}: it applies to every run (Spring applies them top to bottom). */
    private static boolean unconditional(String document) {
        return !java.util.regex.Pattern.compile("(?m)^\\s*spring\\.config\\.activate\\.").matcher(document).find();
    }

    private static java.util.regex.Matcher keyLine(String key, String document) {
        return java.util.regex.Pattern.compile("(?m)^\\s*" + java.util.regex.Pattern.quote(key) + "\\s*[=:].*$").matcher(document);
    }

    /** True when an unconditional document of the .properties text sets {@code key}. */
    static boolean setsKey(String text, String key) {
        List<String> parts = propertiesParts(text);
        for (int i = 0; i < parts.size(); i += 2) {
            if (unconditional(parts.get(i)) && keyLine(key, parts.get(i)).find()) {
                return true;
            }
        }
        return false;
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
     * Merges {@code key=value} lines into a .properties file where Spring reads them (#47, #74). A key already set in an
     * unconditional document is changed (when {@code overwrite}) in the last such document, the one whose value wins; a
     * new key goes to the end of the first unconditional document. Profile documents ({@code spring.config.activate.*})
     * are left alone.
     */
    public static String mergeProperties(String existing, String lines, boolean overwrite) {
        List<String> parts = new ArrayList<>(propertiesParts(existing));
        for (String l : lines.lines().filter(x -> x.contains("=")).toList()) {
            String key = l.substring(0, l.indexOf('=')).strip();
            int defining = -1;
            int first = -1;
            for (int i = 0; i < parts.size(); i += 2) {
                if (!unconditional(parts.get(i))) {
                    continue;
                }
                first = first < 0 ? i : first;
                if (keyLine(key, parts.get(i)).find()) {
                    defining = i;
                }
            }
            if (defining >= 0) {
                if (overwrite) {
                    String doc = parts.get(defining);
                    java.util.regex.Matcher m = keyLine(key, doc);
                    int end = 0;
                    int start = 0;
                    while (m.find()) {   // the last line for the key in that document wins
                        start = m.start();
                        end = m.end();
                    }
                    parts.set(defining, doc.substring(0, start) + l.strip() + doc.substring(end));
                }
                continue;
            }
            int target = first < 0 ? 0 : first;
            String doc = parts.get(target);
            parts.set(target, (doc.isEmpty() || doc.endsWith("\n") ? doc : doc + "\n") + l.strip() + "\n");
        }
        return String.join("", parts);
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
