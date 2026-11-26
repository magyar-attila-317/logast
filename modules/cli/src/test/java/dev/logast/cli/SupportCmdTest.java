package dev.logast.cli;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/** #256: {@code logast support publish}: the shipped support library into the team's own Maven repository. */
class SupportCmdTest {
    @TempDir
    Path tmp;

    private static final String POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <!-- This module was also published with a richer model, Gradle metadata,  -->
              <!-- which should be used instead. Do not delete the following line which  -->
              <!-- is to indicate to Gradle or any Gradle module metadata file consumer  -->
              <!-- that they should prefer consuming it instead. -->
              <!-- do_not_remove: published-with-gradle-metadata -->
              <modelVersion>4.0.0</modelVersion>
              <groupId>dev.logast</groupId>
              <artifactId>ARTIFACT</artifactId>
              <version>0.9.0</version>
              <dependencies>
                <dependency>
                  <groupId>org.slf4j</groupId>
                  <artifactId>slf4j-api</artifactId>
                  <version>2.0.17</version>
                </dependency>
              </dependencies>
            </project>
            """;

    /** What a logast distribution ships in lib/support. */
    private Path shipped() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("lib/support"));
        for (String a : SupportCmd.ARTIFACTS) {
            Files.writeString(dir.resolve(a + "-0.9.0.jar"), "jar of " + a);
            Files.writeString(dir.resolve(a + "-0.9.0-sources.jar"), "sources of " + a);
            Files.writeString(dir.resolve(a + "-0.9.0.pom"), POM.replace("ARTIFACT", a));
        }
        return dir;
    }

    private static int run(String... args) {
        return new CommandLine(SupportCmd.class).execute(args);
    }

    private static String sha1(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(b));
    }

    @Test
    void publishesIntoAFolderRepositoryUnderTheTeamsCoordinates() throws Exception {
        Path dir = shipped();
        Path repo = tmp.resolve("repo");
        assertThat(run("publish", "--from", dir.toString(), "--repo", repo.toUri().toString(), "--group", "com.example.platform",
                "--version", "1.0.0")).isZero();
        Path v = repo.resolve("com/example/platform/logging-support/1.0.0");
        assertThat(v.resolve("logging-support-1.0.0.jar")).hasContent("jar of logging-support");
        assertThat(v.resolve("logging-support-1.0.0-sources.jar")).exists();
        byte[] jar = Files.readAllBytes(v.resolve("logging-support-1.0.0.jar"));
        assertThat(v.resolve("logging-support-1.0.0.jar.sha1")).hasContent(sha1(jar));
        assertThat(v.resolve("logging-support-1.0.0.jar.md5")).exists();
        String pom = Files.readString(v.resolve("logging-support-1.0.0.pom"));
        assertThat(pom).as("the project's coordinates, not a dependency's")
                .contains("<groupId>com.example.platform</groupId>", "<artifactId>logging-support</artifactId>\n  <version>1.0.0</version>",
                        "<groupId>org.slf4j</groupId>", "<version>2.0.17</version>")
                .as("no .module goes with it: Gradle reads the POM").doesNotContain("published-with-gradle-metadata", "richer model");
        assertThat(repo.resolve("com/example/platform/test-capture")).as("services do not depend on it: verify takes it from logast").doesNotExist();

        // a second version: the metadata lists both, the newer as latest
        assertThat(run("publish", "--from", dir.toString(), "--repo", repo.toUri().toString(), "--group", "com.example.platform",
                "--version", "1.10.0")).isZero();
        String meta = Files.readString(repo.resolve("com/example/platform/logging-support/maven-metadata.xml"));
        assertThat(meta).contains("<version>1.0.0</version>", "<version>1.10.0</version>", "<latest>1.10.0</latest>", "<release>1.10.0</release>");
    }

    @Test
    void publishesOverHttpWithBasicAuth() throws Exception {
        Path dir = shipped();
        Map<String, byte[]> put = new ConcurrentHashMap<>();
        Map<String, String> auth = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            auth.put(path, String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            if (ex.getRequestMethod().equals("PUT")) {
                put.put(path, ex.getRequestBody().readAllBytes());
                ex.sendResponseHeaders(201, -1);
            } else {
                ex.sendResponseHeaders(404, -1);   // no metadata yet
            }
            ex.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/maven/releases";
            assertThat(run("publish", "--from", dir.toString(), "--repo", url, "--credentials", "ci-bot:s3cret")).isZero();
        } finally {
            server.stop(0);
        }
        assertThat(put).containsKeys("/maven/releases/dev/logast/logging-support/0.9.0/logging-support-0.9.0.jar",
                "/maven/releases/dev/logast/logging-support/0.9.0/logging-support-0.9.0.pom.sha1",
                "/maven/releases/dev/logast/logging-support/maven-metadata.xml");
        assertThat(new String(put.get("/maven/releases/dev/logast/logging-support/0.9.0/logging-support-0.9.0.jar"), StandardCharsets.UTF_8))
                .as("the shipped version by default").isEqualTo("jar of logging-support");
        String basic = "Basic " + java.util.Base64.getEncoder().encodeToString("ci-bot:s3cret".getBytes(StandardCharsets.UTF_8));
        assertThat(auth.values()).as("every request authenticated").containsOnly(basic);
    }

    /** A refused upload: one line saying what to do, exit 1, no stack trace. */
    @Test
    void aRefusedUploadSaysWhatToDo() throws Exception {
        Path dir = shipped();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.sendResponseHeaders(401, -1);
            ex.close();
        });
        server.start();
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        java.io.PrintStream before = System.err;
        System.setErr(new java.io.PrintStream(err, true, StandardCharsets.UTF_8));
        int code;
        try {
            code = run("publish", "--from", dir.toString(), "--repo", "http://127.0.0.1:" + server.getAddress().getPort() + "/r",
                    "--credentials", "someone:wrong");
        } finally {
            System.setErr(before);
            server.stop(0);
        }
        assertThat(code).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).startsWith("support: PUT dev/logast/logging-support/0.9.0/logging-support-0.9.0.jar: HTTP 401")
                .contains("--credentials user:token").doesNotContain("\tat ");
    }

    @Test
    void aGitHubRepositoryIsItsPackageRegistry() {
        assertThat(List.of("github.com/acme/platform-libs", "https://github.com/acme/platform-libs", "github.com/acme/platform-libs.git")
                .stream().map(SupportCmd::repositoryUrl).distinct().toList()).containsExactly("https://maven.pkg.github.com/acme/platform-libs");
        assertThat(SupportCmd.repositoryUrl("https://nexus.example.com/repository/releases/")).isEqualTo("https://nexus.example.com/repository/releases");
    }

    @Test
    void afterPublishingItSaysWhatComesNextAndHowBuildsReadIt() {
        String github = SupportCmd.nextSteps("dev.logast", "0.5.7", "https://maven.pkg.github.com/acme/platform", null);
        assertThat(github).contains("coordinates: dev.logast:logging-support:0.5.7", "repository: https://maven.pkg.github.com/acme/platform",
                        "logast apply .", "githubAcmePlatformUsername=", "githubAcmePlatformPassword=<token with read:packages>",
                        "packages: read", "Manage Actions access",
                        "https://maven.pkg.github.com/acme/platform/dev/logast/logging-support/0.5.7/logging-support-0.5.7.pom", "Upgrading later")
                .as("no logast.yaml here: nothing recorded").doesNotContain("support.repository set");
        String nexus = SupportCmd.nextSteps("com.example", "1.0.0", "https://nexus.example.com/repository/releases",
                "logast.yaml: support.repository set to https://nexus.example.com/repository/releases");
        assertThat(nexus).startsWith("logast.yaml: support.repository set").doesNotContain("read:packages", "gradle.properties")
                .contains("com/example/logging-support/1.0.0/logging-support-1.0.0.pom");
        assertThat(SupportCmd.versions("<versions><version>0.5.6</version><version>0.5.7</version></versions>".getBytes(StandardCharsets.UTF_8)))
                .containsExactly("0.5.6", "0.5.7");
    }
}
