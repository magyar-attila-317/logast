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
        Path r = root.toAbsolutePath().normalize();
        ScanResult scan = Logast.scan(r, classpath, policy);
        Analysis a = Logast.lastAnalysis;
        String commit = NamedFleet.commit(r);
        try (FactWriter w = new FactWriter(out, Header.of(JavaExtractor.ID, version, repo != null ? repo : NamedFleet.serviceName(r), commit))) {
            new JavaExtractor(a, scan, classpath).extract(r, w);
        }
    }
}
