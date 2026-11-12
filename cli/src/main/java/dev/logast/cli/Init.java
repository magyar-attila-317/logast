package dev.logast.cli;

import dev.logast.Analysis;
import dev.logast.Logast;
import dev.logast.ServiceSetup;
import dev.logast.Traffic;
import dev.logast.VocabularyHarvest;
import dev.logast.orders.Baseline;
import dev.logast.orders.CompactFormat;
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
            System.err.println("logast init: " + r + " has no build.gradle(.kts) / settings.gradle(.kts); "
                    + (Files.exists(r.resolve("pom.xml")) ? "Maven projects are not supported yet." : "run it in a service root."));
            return 2;
        }

        Path policyFile = r.resolve("logast.yaml");
        if (Files.exists(policyFile) && !force) {
            System.out.println("policy:    logast.yaml exists, kept (--force to regenerate)");
        } else {
            // first without vocabulary, so the harvest can leave out what the extended company layers already define
            String style = keyStyle == null ? null : dev.logast.policy.KeyStyle.parse(keyStyle).toString();
            String mode = support == null ? null : new dev.logast.policy.Policy.Support(null, null, support, null).mode();
            Files.writeString(policyFile, ServiceSetup.policyTemplate(extendsList, packs, keepWording, style, mode, null));
            VocabularyHarvest.Result h = VocabularyHarvest.run(r, Logast.policy(r, null), 3);
            Files.writeString(policyFile, ServiceSetup.policyTemplate(extendsList, packs, keepWording, style, mode, VocabularyHarvest.yaml(h)));
            System.out.println("policy:    wrote logast.yaml with " + h.terms().size() + " vocabulary terms harvested from the code"
                    + (h.terms().isEmpty() ? "" : " (review: rename, merge duplicates, drop non-business terms)"));
        }

        List<String> added = ServiceSetup.updateGitignore(r);
        System.out.println("gitignore: " + (added.isEmpty() ? "already set up" : "added " + String.join(" ", added)));

        boolean haveClasspath = !Logast.classpath(r, null).isEmpty();
        if (!noClasspath) {
            Main.Classpath cp = new Main.Classpath();
            cp.root = r;
            int rc = cp.call();
            haveClasspath = rc == 0;
            if (rc != 0) {
                System.err.println("continuing without a classpath; fix the Gradle error and run `logast classpath .`");
            }
        }

        if (!noTraffic || baseline) {
            System.out.println("analysing… (parsing dominates: about 30 s for 2,000 files)");
            Policy policy = Logast.policy(r, null);
            if (!packs.isEmpty()) {
                policy = policy.withPacks(packs);
            }
            List<Path> cp = Logast.classpath(r, null);
            Analysis a;
            if (baseline) {
                ScanResult res = Logast.scan(r, cp, policy, null);
                a = Logast.lastAnalysis;
                Path file = r.resolve(".logast/baseline.json");
                Baseline.of(res).write(file);
                System.out.println("baseline:  " + res.orders().size() + " findings recorded in .logast/baseline.json");
                System.out.print(CompactFormat.summary(res.orders()).indent(11));
            } else {
                a = Logast.analyse(r, cp, policy);
            }
            if (!noTraffic) {
                Path file = r.resolve(".logast/traffic.yaml");
                Traffic existing = Files.exists(file) ? Traffic.load(r, file) : Traffic.none();
                Files.createDirectories(file.getParent());
                Files.writeString(file, Traffic.template(a.entrypoints, existing));
                System.out.println("traffic:   " + a.entrypoints.size() + " entrypoints listed in .logast/traffic.yaml (fill in calls/day)");
            }
        }

        System.out.println();
        System.out.println("next:");
        if (!haveClasspath) {
            System.out.println("  logast classpath .                 # needed for type resolution");
        }
        System.out.println("  $EDITOR logast.yaml                # reorganise the harvested vocabulary; `logast policy .` shows the merge");
        System.out.println("  logast scan . --format summary     # what logast would change");
        if (!baseline) {
            System.out.println("  logast scan . --update-baseline    # for CI: only new findings fail the build");
        }
        return 0;
    }
}
