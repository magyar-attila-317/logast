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
 * Scanner for apply rounds. The first scan parses the whole project from source; later rounds re-parse only the files
 * that changed, against classes compiled from the same tree ({@link IncrementalParser}, #308), and parse in full when
 * that cannot be exact. ({@code logast.parse=full} / {@code LOGAST_PARSE=full}: every round in full.)
 */
public final class RoundScanner implements Function<Path, ScanResult> {
    private final List<Path> classpath;
    private final Function<Path, Policy> policy;

    private final dev.logast.parse.IncrementalParser incremental;
    private ParsedProject last;

    public RoundScanner(List<Path> classpath, Function<Path, Policy> policy) {
        this.classpath = classpath;
        this.policy = policy;
        this.incremental = new dev.logast.parse.IncrementalParser(classpath);
    }

    /** How the last scan parsed and how long it took, for the apply trace (#308). */
    public volatile String lastTiming = "";

    /** The analysis behind the latest scan (entrypoints, policy), for what comes after an apply. */
    public volatile Analysis lastAnalysis;
    /** The lifecycle plan of the latest scan (#125: apply writes .logast/reports/lifecycle.txt from it). */
    public volatile dev.logast.rules.LifecyclePlan lastLifecycle;

    @Override
    public ScanResult apply(Path root) {
        Path abs = root.toAbsolutePath().normalize();
        long t0 = System.nanoTime();
        ParsedProject parsed = null;
        String how = "full parse";
        if (last != null && last.root().equals(abs) && dev.logast.parse.IncrementalParser.enabled()) {
            parsed = incremental.reparse(last);
            how = incremental.lastNote;
        }
        if (parsed == null) {
            parsed = new ProjectParser().parse(abs, classpath);
            if (last == null || !last.root().equals(abs)) {
                incremental.reset();
            }
        }
        last = parsed;
        long t1 = System.nanoTime();
        Analysis a = Analysis.of(parsed, policy.apply(abs)).withTraffic(Traffic.load(abs, null));
        long t2 = System.nanoTime();
        lastAnalysis = a;
        ScanResult r = Rules.scan(a);
        lastLifecycle = Rules.lastLifecycle;
        lastTiming = how + ": parse " + (t1 - t0) / 1_000_000 + " ms, analysis " + (t2 - t1) / 1_000_000 + " ms, rules "
                + (System.nanoTime() - t2) / 1_000_000 + " ms";
        if (System.getenv("LOGAST_TIMING") != null) {
            System.err.println("timing: " + lastTiming);
        }
        return r;
    }
}
