package dev.logast;

import dev.logast.orders.ScanResult;
import dev.logast.parse.Hashing;
import dev.logast.parse.ParsedProject;
import dev.logast.parse.ProjectParser;
import dev.logast.parse.SourceFileInfo;
import dev.logast.policy.Policy;
import dev.logast.rules.Rules;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Scanner for apply rounds: the first scan parses everything; later scans of the same root re-parse only files whose
 * content changed (when compiled classes are on the classpath, otherwise a full parse keeps types correct).
 */
public final class IncrementalScanner implements Function<Path, ScanResult> {
    private final List<Path> classpath;
    private final Function<Path, Policy> policy;
    private ParsedProject last;

    public IncrementalScanner(List<Path> classpath, Function<Path, Policy> policy) {
        this.classpath = classpath;
        this.policy = policy;
    }

    @Override
    public ScanResult apply(Path root) {
        Path abs = root.toAbsolutePath().normalize();
        ProjectParser parser = new ProjectParser();
        long t0 = System.nanoTime();
        String mode = "full";
        if (last != null && last.root().equals(abs) && ProjectParser.hasCompiledClasses(classpath)) {
            Map<String, String> before = new HashMap<>();
            last.files().forEach(f -> before.put(f.relPath(), f.sha256()));
            Set<String> changed = new TreeSet<>();
            Set<String> seen = new HashSet<>();
            for (Path p : javaFiles(abs)) {
                String rel = abs.relativize(p).toString().replace('\\', '/');
                seen.add(rel);
                if (!before.containsKey(rel) || !before.get(rel).equals(sha(p))) {
                    changed.add(rel);
                }
            }
            Set<String> deleted = new TreeSet<>(before.keySet());
            deleted.removeAll(seen);
            last = parser.reparse(last, changed, deleted);
            mode = "incremental (" + changed.size() + " changed)";
        } else {
            last = parser.parse(abs, classpath);
        }
        ScanResult r = Rules.scan(Analysis.of(last, policy.apply(abs)).withTraffic(Traffic.load(abs, null)));
        if (System.getenv("LOGAST_TIMING") != null) {
            System.err.printf("timing: %s scan %d ms%n", mode, (System.nanoTime() - t0) / 1_000_000);
        }
        return r;
    }

    private static List<Path> javaFiles(Path root) {
        Set<String> known = new HashSet<>();
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".java") && p.toString().contains("/src/main/java/")
                    && !p.toString().contains("/build/")).filter(p -> known.add(p.toString())).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha(Path p) {
        try {
            return Hashing.sha256(Files.readString(p));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<SourceFileInfo> files(ParsedProject p) {
        return p.files();
    }
}
