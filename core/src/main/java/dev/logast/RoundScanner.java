package dev.logast;

import dev.logast.orders.ScanResult;
import dev.logast.parse.ParsedProject;
import dev.logast.parse.ProjectParser;
import dev.logast.policy.Policy;
import dev.logast.rules.Rules;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

/**
 * Scanner for apply rounds: every round parses the whole project from source. (Re-parsing only the changed files
 * against compiled classes lost calls into other files whenever the classes did not match the sources, e.g. a copied
 * classpath or files the apply itself added, so later rounds placed MDC scopes differently. Results must not depend
 * on what else is on the classpath.)
 */
public final class RoundScanner implements Function<Path, ScanResult> {
    private final List<Path> classpath;
    private final Function<Path, Policy> policy;

    public RoundScanner(List<Path> classpath, Function<Path, Policy> policy) {
        this.classpath = classpath;
        this.policy = policy;
    }

    /** The analysis behind the latest scan (entrypoints, policy), for what comes after an apply. */
    public volatile Analysis lastAnalysis;

    @Override
    public ScanResult apply(Path root) {
        Path abs = root.toAbsolutePath().normalize();
        long t0 = System.nanoTime();
        ParsedProject parsed = new ProjectParser().parse(abs, classpath);
        Analysis a = Analysis.of(parsed, policy.apply(abs)).withTraffic(Traffic.load(abs, null));
        lastAnalysis = a;
        ScanResult r = Rules.scan(a);
        if (System.getenv("LOGAST_TIMING") != null) {
            System.err.printf("timing: scan %d ms%n", (System.nanoTime() - t0) / 1_000_000);
        }
        return r;
    }
}
