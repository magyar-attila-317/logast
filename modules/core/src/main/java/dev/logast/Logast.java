package dev.logast;

import dev.logast.orders.ScanResult;
import dev.logast.parse.ProjectParser;
import dev.logast.policy.Policy;
import dev.logast.rules.Rules;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Entry point shared by the CLI and the applier: resolves classpath + policy and runs a scan. */
public final class Logast {
    private Logast() {
    }

    /**
     * Explicit file, else every {@code <root>/.logast/classpath*.txt} (one per Gradle project, written by
     * {@code logast classpath}), else empty: then only JDK types resolve and the scan reports unresolved calls.
     */
    public static List<Path> classpath(Path root, Path explicit) {
        if (explicit != null) {
            return ProjectParser.readClasspathFile(explicit);
        }
        return classpathIn(root.resolve(LogastDir.DIR));
    }

    private static final java.util.Set<String> BUILD_FILES = java.util.Set.of("build.gradle", "build.gradle.kts", "settings.gradle",
            "settings.gradle.kts", "gradle.properties", "libs.versions.toml");

    /**
     * #249: a build file changed after {@code .logast/classpath*.txt} was read (an apply reverted with git, a pulled
     * dependency change): the first such file, relative to the root; null when the classpath is current or there is none.
     */
    public static String classpathStale(Path root) {
        Path dir = root.resolve(LogastDir.DIR);
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (var cps = Files.list(dir); var files = Files.walk(root, 3)) {
            var read = cps.filter(p -> p.getFileName().toString().matches("classpath.*\\.txt")).map(Logast::modified)
                    .min(java.util.Comparator.naturalOrder()).orElse(null);
            if (read == null) {
                return null;
            }
            return files.filter(p -> BUILD_FILES.contains(p.getFileName().toString()))
                    .filter(p -> { String rel = root.relativize(p).toString().replace('\\', '/');
                        return !rel.matches("(.*/)?(build|\\.gradle|\\.logast|node_modules)/.*"); })
                    .filter(p -> modified(p).compareTo(read) > 0).map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .sorted().findFirst().orElse(null);
        } catch (IOException | UncheckedIOException e) {
            return null;
        }
    }

    private static java.nio.file.attribute.FileTime modified(Path p) {
        try {
            return Files.getLastModifiedTime(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every {@code classpath*.txt} in {@code dir} (a .logast folder, or a fleet's folder for an added member, #132). */
    public static List<Path> classpathIn(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().matches("classpath.*\\.txt")).sorted()
                    .flatMap(p -> ProjectParser.readClasspathFile(p).stream()).distinct().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Policy policy(Path root, Path explicit) {
        try {
            return Policy.resolve(root, explicit);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read policy: " + e.getMessage(), e);
        }
    }

    /** The analysis behind the most recent {@link #scan} (for CLI diagnostics). */
    public static Analysis lastAnalysis;
    /** #226: the analysis behind this thread's most recent {@link #scan}: scans run in parallel in a fleet refresh. */
    public static final ThreadLocal<Analysis> LAST_ANALYSIS = new ThreadLocal<>();

    public static Analysis analyse(Path root, List<Path> classpath, Policy policy) {
        return Analysis.run(root, classpath, policy);
    }

    public static ScanResult scan(Path root, List<Path> classpath, Policy policy) {
        return scan(root, classpath, policy, null);
    }

    /** @param trafficFile provided entrypoint rates; null → {@code <root>/.logast/decisions/traffic.*} if present */
    public static ScanResult scan(Path root, List<Path> classpath, Policy policy, Path trafficFile) {
        long t0 = System.nanoTime();
        Analysis a = analyse(root, classpath, policy).withTraffic(Traffic.load(root.toAbsolutePath().normalize(), trafficFile));
        lastAnalysis = a;
        LAST_ANALYSIS.set(a);
        long t1 = System.nanoTime();
        ScanResult r = Rules.scan(a);
        if (System.getenv("LOGAST_TIMING") != null) {
            System.err.printf("timing: analysis %d ms, rules %d ms%n", (t1 - t0) / 1_000_000, (System.nanoTime() - t1) / 1_000_000);
        }
        return r;
    }
}
