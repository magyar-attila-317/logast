package dev.logast.apply;

import dev.logast.Fixtures;
import dev.logast.parse.ParsedProject;
import dev.logast.parse.ProjectParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Java package named like a build folder ({@code …common.build}, {@code …out}) is source: parsed and copied into the
 * scratch folder. Before, both left it out, so its types were unresolved and javac could not compile the work tree.
 */
class SourcePackageNamedBuildTest {
    @Test
    void aPackageNamedBuildIsSource(@TempDir Path dir) throws Exception {
        Path pkg = Files.createDirectories(dir.resolve("src/main/java/x/common/build"));
        Files.writeString(pkg.resolve("BuildIdentity.java"), "package x.common.build;\n\npublic record BuildIdentity(String id) {\n}\n");
        Files.createDirectories(dir.resolve("build/classes"));
        Files.writeString(dir.resolve("build/classes/Stale.java"), "class Stale {}\n");
        ParsedProject p = new ProjectParser().parse(dir, Fixtures.classpath());
        assertThat(p.byRelPath()).containsKey("src/main/java/x/common/build/BuildIdentity.java");

        Path work = Applier.copy(dir);
        try {
            assertThat(work.resolve("src/main/java/x/common/build/BuildIdentity.java")).as("copied into the scratch folder").exists();
            assertThat(work.resolve("build/classes")).as("the build output is still left out").doesNotExist();
        } finally {
            try (var s = Files.walk(work.getParent())) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            }
        }
    }
}
