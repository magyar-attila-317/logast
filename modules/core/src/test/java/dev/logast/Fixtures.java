package dev.logast;

import dev.logast.parse.ProjectParser;
import dev.logast.policy.Policy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Access to the sample-app fixture; the analysis is cached because parsing is the slow part. */
public final class Fixtures {
    private static Analysis cached;

    private Fixtures() {
    }

    public static Path root() {
        return Path.of(System.getProperty("logast.fixture.root"));
    }

    public static List<Path> classpath() {
        return ProjectParser.readClasspathFile(Path.of(System.getProperty("logast.fixture.classpath")));
    }

    public static Policy policy() {
        try {
            return Policy.resolve(root(), null);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static synchronized Analysis analysis() {
        if (cached == null) {
            cached = Analysis.run(root(), classpath(), policy()).withTraffic(traffic());
        }
        return cached;
    }

    /**
     * #285: the fixture's measured traffic: its admin endpoints are quiet, so a line logast adds to them may be INFO;
     * every other path has no known rate. (In code: the fixture's .logast/ is ignored by git.)
     */
    public static Traffic traffic() {
        return Traffic.of(Map.of(), Map.of("* /admin/**", 10d), "fixture");
    }
}
