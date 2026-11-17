package dev.logast.rules;

import dev.logast.orders.Edit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A Gradle build file together with the scripts it includes with {@code apply from:} (#80), e.g. the dependencies kept
 * in {@code gradle/dependencies.gradle}. Followed recursively in Gradle's order (the build file first, each applied
 * script where it is applied); a target is resolved against the project directory, as Gradle does. URL or computed
 * targets ({@code "$rootDir/…"}, {@code file(…)}) are not followed and listed in {@link #unresolved()}.
 */
final class BuildScripts {
    private static final Pattern DEPENDENCIES = Pattern.compile("(?m)^dependencies\\s*\\{[^\\n]*$");
    /** {@code apply from: 'x'} / {@code apply(from: "x")} (Groovy), {@code apply(from = "x")} (Kotlin DSL); group 1: the target. */
    private static final Pattern APPLY_FROM = Pattern.compile("(?m)^[ \\t]*apply[ \\t]*(\\()?[ \\t]*from[ \\t]*[:=]([^\\n]*)$");
    private static final Pattern LITERAL = Pattern.compile("([\"'])([^\"'$\\n]+)\\1");

    private final Path root;
    private final Map<Path, String> scripts = new LinkedHashMap<>();
    private final List<String> unresolved = new ArrayList<>();

    private BuildScripts(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** @param root the logast project root, which edit paths are relative to */
    static BuildScripts of(Path root, Path buildFile) {
        BuildScripts s = new BuildScripts(root);
        Path build = buildFile.toAbsolutePath().normalize();
        s.follow(build, build.getParent());
        return s;
    }

    private void follow(Path script, Path projectDir) {
        if (scripts.containsKey(script) || !Files.isRegularFile(script)) {
            return;   // applied twice, or a cycle: read once
        }
        String text = SetupRules.read(script);
        scripts.put(script, text);
        Matcher m = APPLY_FROM.matcher(text);
        while (m.find()) {
            String target = m.group(2).replaceFirst("\\s//.*$", "").strip();
            target = target.endsWith(";") ? target.substring(0, target.length() - 1).strip() : target;
            if (m.group(1) != null && target.endsWith(")")) {
                target = target.substring(0, target.length() - 1).strip();
            }
            Matcher literal = LITERAL.matcher(target);
            if (!literal.matches() || literal.group(2).contains("://")) {
                unresolved.add(target);
                continue;
            }
            Path applied = projectDir.resolve(literal.group(2)).normalize();
            if (!applied.startsWith(root) || !Files.isRegularFile(applied)) {
                unresolved.add(target);
                continue;
            }
            follow(applied, projectDir);
        }
    }

    /** Every script's text, the build file first: what presence checks look at. */
    String text() {
        return String.join("\n", scripts.values());
    }

    /** {@code apply from} targets that were not followed (a URL, a computed path, a missing file). */
    List<String> unresolved() {
        return unresolved;
    }

    /** " Not followed: apply from …." for a report, or "". */
    String unresolvedNote() {
        return unresolved.isEmpty() ? "" : " Not followed: apply from " + String.join(", ", unresolved) + ".";
    }

    /** Where an insertion goes: the first script with a top-level {@code dependencies {}} block, its line and the edit. */
    record Insert(String file, int line, Edit edit) {
    }

    /** null when no script has a top-level {@code dependencies {}} block. */
    Insert insert(List<String> coordinates) {
        for (var e : scripts.entrySet()) {
            String rel = root.relativize(e.getKey()).toString().replace('\\', '/');
            Edit edit = insert(rel, e.getValue(), e.getKey().toString().endsWith(".kts"), coordinates);
            if (edit != null) {
                return new Insert(rel, SetupRules.lineOf(e.getValue(), edit.start()), edit);
            }
        }
        return null;
    }

    Insert insert(String coordinates) {
        return insert(List.of(coordinates));
    }

    /** The dependency lines inserted at the top of the file's top-level {@code dependencies {}} block, in its DSL. */
    static Edit insert(String rel, String text, boolean kts, List<String> coordinates) {
        Matcher m = DEPENDENCIES.matcher(text);
        if (!m.find()) {
            return null;
        }
        int at = Text.nextLine(text, m.end());
        String indent = Text.blank(text, at, Text.lineEnd(text, at)) ? "    " : Text.indentAt(text, at);
        if (indent.isEmpty()) {
            indent = "    ";
        }
        String indentation = indent;
        String lines = coordinates.stream().map(coordinate -> indentation + (kts ? "implementation(\"" + coordinate + "\")"
                : "implementation '" + coordinate + "'")).collect(Collectors.joining("\n"));
        return Edit.insert(rel, at, lines + "\n");
    }
}
