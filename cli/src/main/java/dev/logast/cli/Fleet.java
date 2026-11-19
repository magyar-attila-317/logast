package dev.logast.cli;

import dev.logast.Logast;
import dev.logast.fleet.FleetLinks;
import dev.logast.fleet.FleetReport;
import dev.logast.fleet.ServiceSurface;
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

@Command(name = "fleet", description = "Scan several services and write a compliance report (HTML + JSON snapshot with trend); "
        + "compares them: calls whose trace stops at a service boundary, one outcome counted under several metric names")
final class Fleet implements Callable<Integer> {
    @Parameters(description = "Service roots, or a text file with one root per line (prefix it with @)")
    List<String> services = new ArrayList<>();
    @Option(names = "--out", defaultValue = "logast-fleet", description = "Output directory (keeps previous snapshots for trends)")
    Path out;
    @Option(names = "--vocabulary", description = "No scan: per service, the keys in its code that the vocabulary makes "
            + "aliases, which apply would rename (MDC_KEY_ALIAS)")
    boolean vocabulary;
    @Option(names = "--central", description = "With --vocabulary: the central vocabulary folder; its company.yaml counts "
            + "as extended by every service, so a change shows before the services take it")
    Path central;
    @Option(names = "--classpath-file", description = "One classpath file for every service (default: each service's .logast/classpath*.txt)")
    Path classpathFile;

    @Override
    public Integer call() throws Exception {
        List<Path> roots = roots();
        if (vocabulary) {
            return vocabulary(roots);
        }
        List<FleetReport.Service> result = new ArrayList<>();
        for (Path root : roots) {
            Path r = root.toAbsolutePath().normalize();
            Main.migrate(r);
            Policy policy = Logast.policy(r, null);
            Main.warnOnce(policy);
            System.err.println("scanning " + r);
            ScanResult scan = Logast.scan(r, Logast.classpath(r, classpathFile), policy);
            result.add(FleetReport.service(r.getFileName().toString(), scan, policy.overrides(), policy.packs(),
                    dev.logast.metrics.MeterCatalog.of(Logast.lastAnalysis), ServiceSurface.of(Logast.lastAnalysis, scan)));
        }
        FleetLinks.Result links = FleetLinks.of(result);
        Path html = FleetReport.write(out, result, links);
        result.forEach(s -> System.out.printf("%-28s score %5.1f  high %3d  medium %3d  low %3d%n", s.name(), s.score(),
                s.severity().getOrDefault("high", 0), s.severity().getOrDefault("medium", 0), s.severity().getOrDefault("low", 0)));
        System.out.println();
        System.out.print(FleetLinks.text(links));
        System.out.println();
        System.out.println("report: " + html.toAbsolutePath());
        return 0;
    }

    /** #113: what each service's code would get renamed by the vocabulary (with --central: as if it extended company.yaml). */
    private int vocabulary(List<Path> roots) {
        List<String> extra = new ArrayList<>();
        if (central != null) {
            Path company = Path.of(central.toString().replaceFirst("^~(?=/|$)", System.getProperty("user.home")))
                    .resolve(dev.logast.CentralVocabulary.COMPANY).toAbsolutePath().normalize();
            if (!Files.exists(company)) {
                Out.warn("fleet: " + company + " does not exist; run `logast vocabulary --central " + central + " <service>…` first");
                return 2;
            }
            extra.add(company.toString());
        }
        for (Path root : roots) {
            Path r = root.toAbsolutePath().normalize();
            Main.migrate(r);
            Policy policy = dev.logast.policy.PolicyLoader.load(r, null, extra);
            Main.warnOnce(policy);
            String name = r.getFileName().toString();
            List<dev.logast.CentralVocabulary.Rename> renames = dev.logast.CentralVocabulary.renames(
                    dev.logast.CentralVocabulary.uses(r, name), policy);
            System.out.println(name + ": " + (renames.isEmpty() ? "nothing to rename"
                    : renames.size() + (renames.size() == 1 ? " key" : " keys") + " to rename"));
            for (dev.logast.CentralVocabulary.Rename rn : renames) {
                System.out.println("  " + rn.from() + " -> " + rn.to() + " (" + rn.places().size()
                        + (rn.places().size() == 1 ? " place" : " places") + ")");
                rn.places().forEach(u -> System.out.println("    " + u.file() + ":" + u.line() + " " + u.kind()));
            }
        }
        return 0;
    }

    private List<Path> roots() throws java.io.IOException {
        List<Path> roots = new ArrayList<>();
        for (String s : services) {
            boolean listFile = s.startsWith("@") || Files.isRegularFile(Path.of(s));   // a file of service roots, one per line
            if (listFile) {
                Files.readAllLines(Path.of(s.startsWith("@") ? s.substring(1) : s)).stream().map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("#"))
                        .map(Path::of).forEach(roots::add);
            } else {
                roots.add(Path.of(s));
            }
        }
        return roots;
    }
}
