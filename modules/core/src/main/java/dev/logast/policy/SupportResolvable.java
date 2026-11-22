package dev.logast.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * #127: whether the service's own build resolves {@code dev.logast:logging-support} of this logast's release. The
 * classpath run (the init script {@code logast-classpath.init.gradle}) tries it with the build's repositories and writes
 * {@link #FILE}: {@code resolvable=true|false} and {@code version=<the version it tried>}.
 * <p>
 * Where no layer chooses (no {@code support.mode}, {@code repository} or {@code coordinates}), a resolvable starter
 * means dependency mode with no repository line (the build already reaches it); otherwise the classes are copied in.
 */
public final class SupportResolvable {
    /** Machine input next to the classpath files; written by the classpath run, never edited. */
    public static final String FILE = ".logast/support-resolvable.txt";

    private SupportResolvable() {
    }

    /** TRUE / FALSE when the last classpath run checked {@code version}; null when it did not (no file, another version). */
    public static Boolean read(Path root, String version) {
        if (root == null) {
            return null;
        }
        Path file = root.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Map<String, String> values = new HashMap<>();
        try {
            for (String line : Files.readAllLines(file)) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    values.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (!version.equals(values.get("version")) || !values.containsKey("resolvable")) {
            return null;
        }
        return Boolean.parseBoolean(values.get("resolvable"));
    }

    /** No layer chose how the service gets the support classes: neither {@code mode}, nor a published starter. */
    static boolean undecided(JsonNode merged) {
        JsonNode support = merged.path("support");
        return !support.hasNonNull("mode") && !support.hasNonNull("coordinates")
                && support.path("repository").asText("").isBlank();
    }

    private static final Set<String> COPIED = Set.of("MdcScope.java", "ObservedContext.java", "ProblemTraceId.java", "ReactiveProblemTraceId.java");
    private static final Set<String> SKIPPED_DIRS = Set.of("build", "out", "target", "node_modules", ".git", ".gradle", ".logast", ".idea");

    /**
     * The service carries support classes an earlier apply copied in (vendor mode): it stays with them. Switching a
     * service with copies to the starter is a choice (support.mode: dependency), not something a newly published jar does.
     */
    static boolean copiedIn(Path root) {
        boolean[] found = {false};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return !dir.equals(root) && SKIPPED_DIRS.contains(dir.getFileName().toString())
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (COPIED.contains(file.getFileName().toString())
                            && root.relativize(file).toString().replace('\\', '/').contains("src/main/java/") && copiedByLogast(file)) {
                        found[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return false;
        }
        return found[0];
    }

    private static boolean copiedByLogast(Path file) {
        try (BufferedReader r = Files.newBufferedReader(file)) {
            String first = r.readLine();
            return first != null && first.startsWith("// Copied by logast");
        } catch (IOException | UncheckedIOException e) {
            return false;
        }
    }

    /** In the merged policy tree: dependency mode where no layer chose and the build resolves the starter. */
    static void decide(ObjectNode merged, Path root) {
        if (undecided(merged) && Boolean.TRUE.equals(read(root, Policy.Support.releaseVersion())) && !copiedIn(root)) {
            JsonNode support = merged.path("support");
            ObjectNode s = support.isObject() ? (ObjectNode) support : merged.putObject("support");
            s.put("mode", "dependency");
        }
    }

    /**
     * The one line apply prints when the classes are copied in only because the build cannot resolve the starter;
     * null when a layer chose, the starter resolves, or nothing checked it.
     *
     * @param explicit the policy file given on the command line, or null for {@code <root>/logast.yaml}
     */
    public static String hint(Path root, Path explicit) {
        if (!Boolean.FALSE.equals(read(root, Policy.Support.releaseVersion())) || !undecided(PolicyLoader.merged(root, explicit))
                || copiedIn(root)) {
            return null;
        }
        return "the support jar isn't in this build's repositories: publish it (install.sh) to wire it in instead";
    }
}
