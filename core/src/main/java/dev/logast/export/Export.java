package dev.logast.export;

import dev.logast.Analysis;
import dev.logast.Logast;
import dev.logast.facts.FactWriter;
import dev.logast.facts.Header;
import dev.logast.fleet.NamedFleet;
import dev.logast.orders.ScanResult;
import dev.logast.policy.Policy;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.List;

/** #153: {@code logast export}: a service's facts as a facts file (docs/facts.md). */
public final class Export {
    private Export() {
    }

    /**
     * @param repo    the repository name the facts are about (null: the root folder's name)
     * @param version logast's version, written as the extractor's version
     */
    public static void java(Path root, List<Path> classpath, Policy policy, String repo, String version, Writer out) throws IOException {
        java(root, classpath, policy, repo, version, null, out);
    }

    /** @param flyway null: code facts only; else also the tables (and rows) the migrations create, per environment (#157) */
    public static void java(Path root, List<Path> classpath, Policy policy, String repo, String version, FlywayOptions flyway,
                            Writer out) throws IOException {
        Path r = root.toAbsolutePath().normalize();
        ScanResult scan = Logast.scan(r, classpath, policy);
        Analysis a = Logast.lastAnalysis;
        String commit = NamedFleet.commit(r);
        String extractor = flyway == null ? JavaExtractor.ID : JavaExtractor.ID + "+" + FlywayExtractor.ID;
        try (FactWriter w = new FactWriter(out, Header.of(extractor, version, repo != null ? repo : NamedFleet.serviceName(r), commit))) {
            new JavaExtractor(a, scan, classpath).extract(r, w);
            if (flyway != null) {
                String service = JavaExtractor.serviceName(r, dev.logast.fleet.ServiceSurface.of(a, scan));
                FlywayExtractor fx = new FlywayExtractor(service, flyway.rows(), flyway.image());
                fx.extract(r, w);
                fx.problems().forEach(p -> System.err.println("flyway: " + p));
            }
        }
    }

    /** What {@code --flyway} asks for: the tables whose rows become facts, and the PostgreSQL image. */
    public record FlywayOptions(List<String> rows, String image) {
    }
}
