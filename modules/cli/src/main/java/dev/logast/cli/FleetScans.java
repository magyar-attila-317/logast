package dev.logast.cli;

import dev.logast.Analysis;
import dev.logast.CentralVocabulary;
import dev.logast.Logast;
import dev.logast.LogastDir;
import dev.logast.fleet.CompanyPolicy;
import dev.logast.fleet.FleetHome;
import dev.logast.fleet.FleetExtends;
import dev.logast.fleet.FleetLinks;
import dev.logast.fleet.FleetRepos;
import dev.logast.fleet.FleetScaffold;
import dev.logast.fleet.FleetReport;
import dev.logast.fleet.FleetTidy;
import dev.logast.fleet.NamedFleet;
import dev.logast.orders.Ansi;
import dev.logast.orders.OrderJson;
import dev.logast.orders.ScanResult;
import dev.logast.policy.Policy;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/** Scanning a fleet's members: an added repo's read-only scan, a member's snapshot, a failure that skips one member. */
final class FleetScans {
    private FleetScans() {
    }

    interface MemberScan {
        void run() throws IOException;
    }

    /**
     * #136: one member that fails to scan (a repo that is no Java service, a crash on its code) is reported and skipped;
     * the other members are still scanned, and the failed one's snapshot stays as it was. True when it was scanned.
     */
    /** #193: a member's scan; null when it was scanned, else why not (the whole error with LOGAST_DEBUG). */
    static String scanError(MemberScan scan) {
        try {
            scan.run();
            return null;
        } catch (IOException | RuntimeException e) {
            if (System.getenv("LOGAST_DEBUG") != null) {
                e.printStackTrace();
            }
            return String.valueOf(e);
        }
    }

    static boolean skipOnFailure(String service, MemberScan scan) {
        try {
            scan.run();
            return true;
        } catch (IOException | RuntimeException e) {
            Out.warn("fleet: " + service + " skipped, its scan failed: " + e
                    + " (its snapshot stays as it was; `logast scan` in the repo shows the whole error)");
            if (System.getenv("LOGAST_DEBUG") != null) {
                e.printStackTrace();
            }
            return false;
        }
    }

    /** #184: the analysis of the member this thread scanned last, for its export (#226: per thread, scans run in parallel). */
    static final ThreadLocal<dev.logast.export.Export.Analysed> lastScanned = new ThreadLocal<>();
    static final ThreadLocal<Path> lastScannedRoot = new ThreadLocal<>();

    /**
     * #132: scans a member {@code fleet add} registered: the analysis of `logast scan`, with the policy defaults →
     * the fleet's company.yaml → the repo's own logast.yaml if it has one. Every output (classpath, orders.json,
     * coverage, reports) goes to {@code <fleet>/services/<service>/}, laid out like .logast; nothing is written into the
     * repo (no .logast migration, no decision files, no traffic or retry worksheets). The classpath: {@code classpathFile},
     * else the repo's own Gradle run with its output and project cache in the fleet folder.
     */
    static NamedFleet.Snapshot scanAdded(Path fleetDir, String service, Path root, Path classpathFile) throws IOException {
        return scanAdded(fleetDir, service, root, classpathFile, false);
    }

    /** @param noBuilds #147: never run the member's Gradle; the classpath its last build left, if any */
    static NamedFleet.Snapshot scanAdded(Path fleetDir, String service, Path root, Path classpathFile, boolean noBuilds)
            throws IOException {
        Path r = root.toAbsolutePath().normalize();
        Path out = NamedFleet.outputDir(fleetDir, service);
        Policy policy = dev.logast.policy.PolicyLoader.forFleet(r, fleetDir);
        Main.warnOnce(policy);
        List<Path> cp = classpathFile != null ? Logast.classpath(r, classpathFile)
                : noBuilds ? Logast.classpathIn(out) : addedClasspath(service, r, out);
        if (!Out.Progress.quiet) {
            System.err.println("scanning " + r + " (read-only; results in " + out + ")");
        }
        ScanResult scan = Logast.scan(r, cp, policy);
        Analysis a = Logast.LAST_ANALYSIS.get();
        lastScanned.set(new dev.logast.export.Export.Analysed(a, scan, cp));   // #184: the export reuses it
        lastScannedRoot.set(r);
        Path orders = LogastDir.in(out, LogastDir.ORDERS);
        Files.createDirectories(orders.getParent());
        OrderJson.MAPPER.writeValue(orders.toFile(), scan);
        if (policy.packSelected("logging")) {
            a.ledger.writeTo(LogastDir.in(out, LogastDir.COVERAGE), a, scan.orders(), Map.of());
        }
        Reports.writeTo(out, a, dev.logast.rules.Rules.LAST_LIFECYCLE.get());
        return NamedFleet.snapshot(r, scan, a, policy);
    }

    /** The added member's classpath from its own Gradle (reads only), written to {@code out}; the last one if Gradle fails. */
    static List<Path> addedClasspath(String service, Path r, Path out) throws IOException {
        boolean gradle = Files.exists(r.resolve("gradlew")) || Files.exists(r.resolve("build.gradle"))
                || Files.exists(r.resolve("build.gradle.kts"));
        if (!gradle) {
            Out.warn("fleet: " + service + " has no Gradle build at " + r + ": scanned without a classpath (--classpath-file gives one)");
            return Logast.classpathIn(out);
        }
        Main.Classpath.Result res;
        try (Out.Progress p = new Out.Progress("running Gradle in " + service + " to read the classpath (nothing is compiled)")) {
            res = Main.Classpath.write(r, out);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            res = new Main.Classpath.Result(130, 0, false);
        }
        if (res.printIfFailed().exitCode() != 0) {
            Out.warn("fleet: " + service + ": Gradle failed (exit " + res.exitCode() + "); "
                    + (Logast.classpathIn(out).isEmpty() ? "scanned without a classpath" : "scanned with the classpath of its last scan"));
        }
        return Logast.classpathIn(out);
    }

    /**
     * #124: a member's scan or apply leaves its snapshot in the fleet folder (and registers the service there when this
     * machine's member list lacks it). Never fails the command: one warning line instead.
     */
    static void snapshot(Path root, ScanResult r, Analysis a, Policy policy) {
        try {   // #133: the comment after `- fleet:<name>` says where it resolves on this machine
            FleetExtends.refresh(dev.logast.policy.PolicyLoader.defaultFile(root));
        } catch (RuntimeException e) {
            Out.warn("fleet: " + e.getMessage());
        }
        String name = NamedFleet.of(root);
        if (name == null || r == null || a == null) {
            return;
        }
        try {
            Path dir = FleetHome.fleetDir(FleetHome.Env.current(), name).dir();
            if (!NamedFleet.exists(dir)) {
                return;   // the policy's warning already says this machine does not know the fleet
            }
            if (!NamedFleet.members(dir).containsKey(NamedFleet.serviceName(root))) {
                NamedFleet.join(dir, name, root);
            }
            NamedFleet.write(dir, NamedFleet.snapshot(root, r, a, policy));
            Map<String, NamedFleet.Snapshot> snaps = NamedFleet.snapshots(dir);
            List<CompanyPolicy.Suggestion> suggestions = CompanyPolicy.refresh(dir, name, snaps.values());   // #129
            // #141: the fleet's page follows every member's snapshot
            List<FleetReport.Service> services = snaps.values().stream().map(NamedFleet.Snapshot::report).toList();
            FleetLinks.Result links = FleetLinks.of(services);
            FleetReport.writeDaily(dir.resolve("report"), services, links, suggestions.stream().map(CompanyPolicy.Suggestion::text).toList(),
                    dev.logast.fleet.FleetNext.steps(name, dir, NamedFleet.members(dir), NamedFleet.joined(dir), snaps, suggestions,
                            links, NamedFleet.vocabulary(dir, snaps.values())));
        } catch (RuntimeException e) {
            Out.warn("fleet " + name + ": no snapshot written (" + e.getMessage() + "); the fleet overview shows the previous one");
        }
    }
}
