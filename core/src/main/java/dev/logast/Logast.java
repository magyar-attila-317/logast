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
        Path dir = root.resolve(".logast");
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

    public static Analysis analyse(Path root, List<Path> classpath, Policy policy) {
        return Analysis.run(root, classpath, policy);
    }

    public static ScanResult scan(Path root, List<Path> classpath, Policy policy) {
        return scan(root, classpath, policy, null);
    }

    /** @param trafficFile provided entrypoint rates; null → {@code <root>/.logast/traffic.*} if present */
    public static ScanResult scan(Path root, List<Path> classpath, Policy policy, Path trafficFile) {
        long t0 = System.nanoTime();
        Analysis a = analyse(root, classpath, policy).withTraffic(Traffic.load(root.toAbsolutePath().normalize(), trafficFile));
        lastAnalysis = a;
        long t1 = System.nanoTime();
        ScanResult r = Rules.scan(a);
        if (System.getenv("LOGAST_TIMING") != null) {
            System.err.printf("timing: analysis %d ms, rules %d ms%n", (t1 - t0) / 1_000_000, (System.nanoTime() - t1) / 1_000_000);
        }
        return r;
    }
}
