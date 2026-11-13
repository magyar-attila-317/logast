package dev.logast.cli;

import dev.logast.Analysis;
import dev.logast.Logast;
import dev.logast.ServiceSetup;
import dev.logast.Traffic;
import dev.logast.VocabularyHarvest;
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
    @Option(names = "--pack", split = ",", description = "Rule packs for the policy (default: logging,tracing)")
    List<String> packs = new ArrayList<>();
    @Option(names = "--keep-wording", description = "Legacy service: don't rewrite existing message wording")
    boolean keepWording;
    @Option(names = "--key-style", description = "Spelling of MDC and [k=v] keys: dotted (order.id), camel (orderId), pascal (OrderId), snake (order_id)")
    String keyStyle;
    @Option(names = "--support", description = "How generated code gets MdcScope/ObservedContext: dependency (the starter "
            + "library, must be resolvable from your repositories) or vendor (classes copied into the service)")
    String support;
    @Option(names = "--baseline", description = "Scan once and record today's findings as the CI baseline")
    boolean baseline;
    @Option(names = "--no-classpath", description = "Skip the Gradle run (scans then resolve far fewer types)")
    boolean noClasspath;
    @Option(names = "--no-traffic", description = "Skip writing the .logast/traffic.yaml template")
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

        Path policyFile = r.resolve("logast.yaml");
        if (Files.exists(policyFile) && !force) {
            Out.kept("logast.yaml", "already there, kept (--force writes a fresh one)");
        } else {
            // first without vocabulary, so the harvest can leave out what the extended company layers already define
            // the key style: given, else the one the code already uses most, else the default (camel); always written out
            dev.logast.policy.KeyStyle detected = keyStyle == null ? VocabularyHarvest.detectStyle(r) : null;
            String style = keyStyle != null ? dev.logast.policy.KeyStyle.parse(keyStyle).toString()
                    : (detected != null ? detected : dev.logast.policy.KeyStyle.CAMEL).toString();
            String mode = support == null ? null : new dev.logast.policy.Policy.Support(null, null, support, null).mode();
            String logFormat = ServiceSetup.detectLogFormat(r);
            Files.writeString(policyFile, ServiceSetup.policyTemplate(extendsList, packs, keepWording, style, mode, logFormat, null));
            VocabularyHarvest.Result h = VocabularyHarvest.run(r, Logast.policy(r, null), 3);
            Files.writeString(policyFile, ServiceSetup.policyTemplate(extendsList, packs, keepWording, style, mode, logFormat, VocabularyHarvest.yaml(h)));
            Out.step("logast.yaml", "your settings: which checks run, key style " + style
                    + (keyStyle != null ? "" : detected != null ? " (what the code uses most)" : " (default)")
                    + ", log format " + (logFormat != null ? logFormat + " (what the service already uses)" : "ecs (default)") + ", and "
                    + h.terms().size() + " business terms found in the code (review them)");
        }

        List<String> added = ServiceSetup.updateGitignore(r);
        if (added.isEmpty()) {
            Out.kept(".gitignore", "already ignores .logast/ (except the shared files)");
        } else {
            Out.step(".gitignore", "ignores .logast/, but keeps baseline.json, traffic.yaml and README.md shared");
        }
        Path readme = r.resolve(".logast/README.md");
        Files.createDirectories(readme.getParent());
        Files.writeString(readme, ServiceSetup.logastReadme());
        Out.step(".logast/README.md", "explains every file in .logast/ and which ones go into git");

        boolean haveClasspath = !Logast.classpath(r, null).isEmpty();
        if (noClasspath) {
            Out.skipped(".logast/classpath-*.txt", "skipped (--no-classpath); run `logast classpath .` before scanning");
        } else {
            long t0 = System.nanoTime();
            Out.progress("  running Gradle to read the classpath (nothing is compiled)…");
            Main.Classpath.Result cp = Main.Classpath.write(r);
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
                Path file = r.resolve(".logast/traffic.yaml");
                Traffic existing = Files.exists(file) ? Traffic.load(r, file) : Traffic.none();
                Files.writeString(file, Traffic.template(a.entrypoints, existing));
                Out.step(".logast/traffic.yaml", a.entrypoints.size() + " entrypoints; optional: add calls/day so logast can judge log volume");
            }
        }
        if (!baseline) {
            Out.skipped(".logast/baseline.json", "not recorded (add --baseline, or later `logast scan . --update-baseline`)");
        }

        List<String[]> next = new ArrayList<>();
        if (!haveClasspath) {
            next.add(new String[]{"logast classpath .", "lets logast understand types (needs a working Gradle build)"});
        }
        next.add(new String[]{"open logast.yaml", "rename/merge the business terms; the comments explain every setting"});
        next.add(new String[]{"logast scan . --format summary", "what logast would change, per rule"});
        next.add(new String[]{"logast apply . --auto", "preview the automatic fixes (add --write to change files)"});
        Out.next(next);
        return 0;
    }
}
