package dev.logast.cli;

import dev.logast.Logast;
import dev.logast.fleet.FleetReport;
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

@Command(name = "fleet", description = "Scan several services and write a compliance report (HTML + JSON snapshot with trend)")
final class Fleet implements Callable<Integer> {
    @Parameters(description = "Service roots, or a text file with one root per line (prefix it with @)")
    List<String> services = new ArrayList<>();
    @Option(names = "--out", defaultValue = "logast-fleet", description = "Output directory (keeps previous snapshots for trends)")
    Path out;

    @Override
    public Integer call() throws Exception {
        List<Path> roots = new ArrayList<>();
        for (String s : services) {
            if (s.startsWith("@")) {
                Files.readAllLines(Path.of(s.substring(1))).stream().map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("#"))
                        .map(Path::of).forEach(roots::add);
            } else {
                roots.add(Path.of(s));
            }
        }
        List<FleetReport.Service> result = new ArrayList<>();
        for (Path root : roots) {
            Path r = root.toAbsolutePath().normalize();
            Policy policy = Logast.policy(r, null);
            System.err.println("scanning " + r);
            ScanResult scan = Logast.scan(r, Logast.classpath(r, null), policy);
            result.add(FleetReport.service(r.getFileName().toString(), scan, policy.overrides(), policy.packs()));
        }
        Path html = FleetReport.write(out, result);
        result.forEach(s -> System.out.printf("%-28s score %5.1f  high %3d  medium %3d  low %3d%n", s.name(), s.score(),
                s.severity().getOrDefault("high", 0), s.severity().getOrDefault("medium", 0), s.severity().getOrDefault("low", 0)));
        System.out.println("report: " + html.toAbsolutePath());
        return 0;
    }
}
