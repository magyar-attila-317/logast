package dev.logast.cli;

import dev.logast.Analysis;
import dev.logast.Logast;
import dev.logast.ServiceSetup;
import dev.logast.Traffic;
import dev.logast.orders.Baseline;
import dev.logast.orders.Ansi;
import dev.logast.orders.ScanResult;
import dev.logast.policy.Policy;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "init", description = "Set up logast in a service (run in its root): logast.yaml, .gitignore, classpath, "
        + "traffic template, optional CI baseline. Safe to re-run: existing files are kept.")
final class Init implements Callable<Integer> {
    @Parameters(index = "0", defaultValue = ".", description = "Service root (default: current directory)")
    Path root;
    @Option(names = "--extends", split = ",", description = "Company/area policy layers to extend (files or URLs)")
    List<String> extendsList = new ArrayList<>();
    @Option(names = "--pack", split = ",", completionCandidates = Main.PackNames.class,
            description = "Rule packs for the policy: ${COMPLETION-CANDIDATES} (default: all)")
    List<String> packs = new ArrayList<>();
    @Option(names = "--keep-wording", description = "Legacy service: don't rewrite existing message wording")
    boolean keepWording;
    @Option(names = "--key-style", description = "Spelling of MDC and [k=v] keys: dotted (order.id), camel (orderId), pascal (OrderId), snake (order_id)")
    String keyStyle;
    @Option(names = "--support", description = "How generated code gets MdcScope/ObservedContext: vendor (classes copied into "
            + "the service, the default) or dependency (the starter library, must be resolvable from your repositories)")
    String support;
    @Option(names = "--baseline", description = "Scan once and record today's findings as the CI baseline")
    boolean baseline;
    @Option(names = "--no-classpath", description = "Skip the Gradle run (scans then resolve far fewer types)")
    boolean noClasspath;
    @Option(names = "--no-traffic", description = "Skip writing the .logast/decisions/traffic.yaml template")
    boolean noTraffic;
    @Option(names = "--force", description = "Overwrite an existing logast.yaml")
    boolean force;

    @Override
    public Integer call() throws Exception {
        Path r = root.toAbsolutePath().normalize();
        if (!ServiceSetup.isGradleProject(r)) {
            Out.warn("logast init: " + r + " has no build.gradle(.kts) / settings.gradle(.kts); "
                    + (Files.exists(r.resolve("pom.xml")) ? "Maven projects are not supported yet." : "run it in a service root."));
            return 2;
        }
        Out.title("logast init  " + Ansi.dim(r.toString()));
        System.out.println(Ansi.dim("Sets up this service once. Re-running keeps what you already edited."));
        System.out.println();

        Main.migrate(r);
        ServiceSetup.Bootstrap set = ServiceSetup.bootstrap(r,
                new ServiceSetup.Choices(extendsList, packs, keepWording, keyStyle, support), force);
        if (!set.policyWritten()) {
            Out.kept("logast.yaml", "already there, kept (--force writes a fresh one)");
        } else {
            Out.step("logast.yaml", "your settings: which checks run, key style " + set.keyStyle()
                    + (keyStyle != null ? "" : set.styleDetected() ? " (what the code uses most)" : " (default)")
                    + ", log format " + (set.logFormat() != null ? set.logFormat() + " (what the service already uses)" : "logstash (default)")
                    + ", and " + set.terms() + " keys found in the code, one term each (organise them)");
        }
        if (set.gitignoreAdded().isEmpty()) {
            Out.kept(".gitignore", "already ignores .logast/ (except the shared files)");
        } else {
            Out.step(".gitignore", "ignores .logast/, but keeps baseline.json, decisions/ and README.md shared");
        }
        Out.step(".logast/README.md", "explains every file in .logast/ and which ones go into git");

        boolean haveClasspath = !Logast.classpath(r, null).isEmpty();
        if (noClasspath) {
            Out.skipped(".logast/classpath-*.txt", "skipped (--no-classpath); run `logast classpath .` before scanning");
        } else {
            long t0 = System.nanoTime();
            Out.progress("  running Gradle to read the classpath (nothing is compiled)…");
            Main.Classpath.Result cp = Main.Classpath.write(r).printIfFailed();
            haveClasspath = cp.exitCode() == 0;
            if (haveClasspath) {
                Out.step(".logast/classpath-*.txt", cp.entries() + " jars and class folders (" + Out.seconds(t0)
                        + "): lets logast understand types; re-run `logast classpath .` after dependency changes");
            } else {
                Out.failed(".logast/classpath-*.txt", "Gradle failed (exit " + cp.exitCode() + "); fix it and run `logast classpath .`");
            }
        }

        if (!noTraffic || baseline) {
            long t0 = System.nanoTime();
            Out.progress("  analysing the code (about 30 s for 2,000 files)…");
            Policy policy = Logast.policy(r, null);
            if (!packs.isEmpty()) {
                policy = policy.withPacks(packs);
            }
            List<Path> cp = Logast.classpath(r, null);
            Analysis a;
            if (baseline) {
                ScanResult res = Logast.scan(r, cp, policy, null);
                a = Logast.lastAnalysis;
                Baseline.of(res).write(r.resolve(".logast/baseline.json"));
                Out.step(".logast/baseline.json", res.orders().size() + " findings recorded (" + Out.seconds(t0)
                        + "): CI fails only on findings that are not in it");
            } else {
                a = Logast.analyse(r, cp, policy);
            }
            if (!noTraffic) {
                Path file = r.resolve(dev.logast.LogastDir.TRAFFIC);
                Traffic existing = Files.exists(file) ? Traffic.load(r, file) : Traffic.none();
                Files.createDirectories(file.getParent());
                Files.writeString(file, dev.logast.Schemas.stamp(file, Traffic.template(a.entrypoints, existing)));
                Out.step(".logast/decisions/traffic.yaml", a.entrypoints.size() + " entrypoints; optional: add calls/day so logast can judge log volume");
            }
        }
        if (!baseline) {
            Out.skipped(".logast/baseline.json", "not recorded (add --baseline, or later `logast scan . --update-baseline`)");
        }

        // #122: the flow, in order; only apply is needed: it reads the classpath itself, and every decision has a default
        List<String[]> next = new ArrayList<>();
        next.add(new String[]{"logast apply .", "changes the code; every change is compiled before it is kept (--dry-run: a preview)"
                + (haveClasspath ? "" : "; reads the classpath first (a working Gradle build)")});
        next.add(new String[]{"git diff", "review the result"});
        next.add(new String[]{"optional: logast review", "message texts, metrics, retries in .logast/decisions/; each has a default. "
                + "Then logast apply . again, on the migrated code (don't revert it)"});
        next.add(new String[]{"optional: open logast.yaml", "organise the found terms as its example shows; the comments explain every setting"});
        Out.next(next);
        return 0;
    }
}
