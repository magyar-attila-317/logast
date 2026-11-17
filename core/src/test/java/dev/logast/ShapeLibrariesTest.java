package dev.logast;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** libraries.txt names every jar by its coordinate, from Gradle's cache or a Maven repository. */
class ShapeLibrariesTest {
    @TempDir
    Path tmp;

    @Test
    void gradleCachePathsGiveCoordinates() {
        assertThat(ShapeExport.gradleCoordinate("/u/.gradle/caches/modules-2/files-2.1/org.projectlombok/lombok/1.18.46/a5cf/lombok-1.18.46.jar"))
                .isEqualTo("org.projectlombok:lombok:1.18.46");
        assertThat(ShapeExport.gradleCoordinate("/u/libs/lombok.jar")).isNull();
    }

    @Test
    void mavenRepositoryPathsGiveCoordinates() {
        assertThat(ShapeExport.mavenCoordinate("/u/.m2/repository/com/acme/platform/acme-calendar/0.3.0/acme-calendar-0.3.0.jar"))
                .isEqualTo("com.acme.platform:acme-calendar:0.3.0");
        assertThat(ShapeExport.mavenCoordinate("/u/.m2/repository/org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17-sources.jar"))
                .isEqualTo("org.slf4j:slf4j-api:2.0.17");
        assertThat(ShapeExport.mavenCoordinate("/u/libs/acme.jar")).isNull();
    }

    @Test
    void publicGroupsAreKnownOnesOnly() {
        assertThat(ShapeExport.publicName("org.springframework.web.bind.annotation.GetMapping", Set.of())).isTrue();
        assertThat(ShapeExport.publicName("org.projectlombok", Set.of())).isTrue();
        assertThat(ShapeExport.publicName("commons-io", Set.of())).isTrue();
        assertThat(ShapeExport.publicName("java.util.List", Set.of())).isTrue();
        assertThat(ShapeExport.publicName("org.springframeworkx.Foo", Set.of())).as("a prefix ends at a dot").isFalse();
        assertThat(ShapeExport.publicName("org.innerkit.gate.QuorumGate", Set.of())).isFalse();
        assertThat(ShapeExport.publicName("org.innerkit.gate.QuorumGate", Set.of("org.innerkit"))).isTrue();
    }

    /**
     * #59: a company library under a group of its own (not the application's package prefix) is not public: neither
     * its coordinate nor its type and method names leave in the export, unless the person names it with --public-group.
     */
    @Test
    void aLibraryOutsideTheKnownPublicGroupsIsAnonymised() throws Exception {
        Path jar = innerLibraryJar();
        Path app = tmp.resolve("svc");
        Path src = app.resolve("src/main/java/com/example/svc");
        Files.createDirectories(src);
        Files.writeString(src.resolve("SvcApplication.java"), """
                package com.example.svc;

                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class SvcApplication {
                }
                """);
        Files.writeString(src.resolve("NightlyJob.java"), """
                package com.example.svc;

                import org.innerkit.gate.QuorumGate;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                public class NightlyJob {
                    private static final Logger log = LoggerFactory.getLogger(NightlyJob.class);
                    private final QuorumGate gate = new QuorumGate();

                    public void run(String id) {
                        log.info("done {}", gate.admitPilgrim(id));
                        org.innerkit.gate.QuorumGate other = QuorumGate.vestry();
                        log.info("done {}", other.admitPilgrim(id));
                    }
                }
                """);
        // a company library missing from the classpath: its calls and annotations are unresolved, still not public
        Files.writeString(src.resolve("WeeklyJob.java"), """
                package com.example.svc;

                import org.innerkit.vault.CrypticVault;
                import org.innerkit.vault.SealedRite;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                public class WeeklyJob {
                    private static final Logger log = LoggerFactory.getLogger(WeeklyJob.class);

                    @SealedRite
                    public void run() {
                        CrypticVault vault = CrypticVault.summonWarden();
                        log.info("done {}", vault.unsealChamber());
                    }
                }
                """);
        List<Path> cp = new ArrayList<>(Fixtures.classpath());
        cp.add(jar);
        Analysis a = Logast.analyse(app, cp, dev.logast.policy.Policy.defaults());

        Path out = tmp.resolve("shape");
        ShapeExport.write(a, out, tmp.resolve("mapping.txt"));
        assertThat(Files.readString(out.resolve("libraries.txt"))).contains("org.slf4j:slf4j-api:").doesNotContain("innerkit");
        String export = exportText(out);
        for (String word : List.of("innerkit", "quorum", "pilgrim", "vestry", "cryptic", "sealed", "rite", "summon",
                "warden", "unseal", "chamber")) {
            assertThat(export).as("the export still says " + word).doesNotContain(word);
        }
        assertThat(export).as("public names stay").contains("loggerfactory").contains("org.slf4j");

        // named public by the person: kept, and listed for review
        ShapeExport.write(a, out, tmp.resolve("mapping.txt"), Set.of("org.innerkit"));
        assertThat(Files.readString(out.resolve("libraries.txt"))).contains("org.innerkit:innerkit-gate:1.2.0");
        assertThat(exportText(out)).contains("org.innerkit.gate.quorumgate").contains("admitpilgrim");
        assertThat(Files.readString(out.resolve("kept-words.txt"))).contains("quorum").contains("pilgrim");
    }

    private static String exportText(Path out) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> files = Files.walk(out)) {
            for (Path f : files.filter(Files::isRegularFile).filter(f -> !f.getFileName().toString().equals("kept-words.txt")).toList()) {
                sb.append(out.relativize(f)).append('\n').append(Files.readString(f)).append('\n');
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /** A library jar in Gradle's cache under a group that is not a known public one. */
    private Path innerLibraryJar() throws IOException {
        Path src = tmp.resolve("lib-src/org/innerkit/gate");
        Files.createDirectories(src);
        Files.writeString(src.resolve("QuorumGate.java"), """
                package org.innerkit.gate;

                public class QuorumGate {
                    public static QuorumGate vestry() {
                        return new QuorumGate();
                    }

                    public String admitPilgrim(String id) {
                        return id;
                    }
                }
                """);
        Path classes = tmp.resolve("lib-classes");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = ToolProvider.getSystemJavaCompiler().run(null, null, err, "-d", classes.toString(), src.resolve("QuorumGate.java").toString());
        assertThat(rc).as(err.toString()).isZero();
        Path jar = tmp.resolve("gradle/caches/modules-2/files-2.1/org.innerkit/innerkit-gate/1.2.0/0a1b2c/innerkit-gate-1.2.0.jar");
        Files.createDirectories(jar.getParent());
        try (OutputStream os = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(os);
             Stream<Path> files = Files.walk(classes)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                jos.putNextEntry(new JarEntry(classes.relativize(f).toString().replace('\\', '/')));
                jos.write(Files.readAllBytes(f));
                jos.closeEntry();
            }
        }
        return jar;
    }
}
