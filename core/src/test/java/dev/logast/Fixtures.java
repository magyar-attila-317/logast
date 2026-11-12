package dev.logast;

import dev.logast.parse.ProjectParser;
import dev.logast.policy.Policy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

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
            cached = Analysis.run(root(), classpath(), policy());
        }
        return cached;
    }
}
