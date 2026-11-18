package dev.logast.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** #56: what the CLI says about a policy that means nothing (error) or probably the wrong thing (warning). */
class PolicyCheckCliTest {
    @TempDir
    Path tmp;

    record Run(int exit, String err, String out) {
    }

    private Path project(String policy) throws IOException {
        Path app = tmp.resolve("app");
        Path pkg = app.resolve("src/main/java/demo");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Ok.java"), "package demo;\n\nclass Ok {\n    int one() {\n        return 1;\n    }\n}\n");
        Files.writeString(app.resolve("logast.yaml"), policy);
        return app;
    }

    private static Run run(String... args) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int rc;
        try {
            System.setOut(new PrintStream(o, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(e, true, StandardCharsets.UTF_8));
            rc = new CommandLine(new Main()).execute(args);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        return new Run(rc, e.toString(StandardCharsets.UTF_8).replaceAll("\u001b\\[[0-9;]*m", ""),
                o.toString(StandardCharsets.UTF_8).replaceAll("\u001b\\[[0-9;]*m", ""));
    }

    @Test
    void aWarningIsPrintedOnceAndTheScanRuns() throws IOException {
        Path app = project("metrics:\n  catalogClass: com.example.metrics.BusinessMetrics\n");
        Run r = run("scan", app.toString(), "--format", "json");
        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.err()).containsOnlyOnce("policy: logast.yaml: metrics.catalogClass: is only used with metrics.catalog: single");
    }

    @Test
    void anErrorStopsTheScan() throws IOException {
        Path app = project("rules:\n  LOG_FORAMT: { enabled: false }\n");
        Run r = run("scan", app.toString(), "--format", "json");
        assertThat(r.exit()).isNotZero();
        assertThat(r.err()).contains("logast.yaml: rules.LOG_FORAMT: unknown rule id; did you mean LOG_FORMAT?");
    }

    @Test
    void unknownRuleAndPackOptionsAreRejected() throws IOException {
        Path app = project("packs: [logging]\n");
        Run rule = run("scan", app.toString(), "--format", "json", "--rule", "LOG_FORAMT");
        assertThat(rule.exit()).isEqualTo(2);
        assertThat(rule.err()).contains("--rule LOG_FORAMT: unknown rule id; did you mean LOG_FORMAT?");
        Run pack = run("scan", app.toString(), "--format", "json", "--pack", "loging");
        assertThat(pack.exit()).isNotZero();
        assertThat(pack.err()).contains("--pack loging: unknown pack; did you mean logging?");
    }

    /** #56: logast policy --check: names that match nothing in the code, ignore comments naming no rule; exit 0 / 1 / 2. */
    @Test
    void policyCheckFindsNamesThatMatchNothing() throws IOException {
        Path app = project("packs: [logging, audit]\naudit:\n  emitters: [demo.Audit]\nvocabulary:\n  order:\n    mdc:\n"
                + "      key: orderId\n      sources:\n        types:\n          demo.Order: \"id()\"\n");
        Files.writeString(app.resolve("src/main/java/demo/AuditLog.java"),
                "package demo;\n\nclass AuditLog {\n    void record() {\n    }\n}\n");
        Files.writeString(app.resolve("src/main/java/demo/Ok.java"), "package demo;\n\nclass Ok {\n    int one() {\n"
                + "        return 1; // logast:ignore LOG_FORAMT\n    }\n}\n");
        Run r = run("policy", app.toString(), "--check");
        assertThat(r.exit()).as(r.out() + r.err()).isEqualTo(1);
        assertThat(r.out()).contains("logast.yaml: audit.emitters[0]: demo.Audit is not in the code or on the classpath")
                .contains("logast.yaml: vocabulary.order.mdc.sources.types: demo.Order is not in the code or on the classpath")
                .contains("src/main/java/demo/Ok.java:5: logast:ignore LOG_FORAMT names no rule, so it silences nothing; "
                        + "did you mean LOG_FORMAT?")
                .contains("3 warning(s)");
        assertThat(run("scan", app.toString(), "--format", "json").err()).as("scan says it too")
                .contains("policy: src/main/java/demo/Ok.java:5: logast:ignore LOG_FORAMT names no rule");

        Run clean = run("policy", project("packs: [logging]\n").toString(), "--check");
        assertThat(clean.exit()).as(clean.out() + clean.err()).isZero();
        assertThat(clean.out()).contains("policy ok");
        assertThat(run("policy", project("packs: [loging]\n").toString(), "--check").exit()).as("a load error").isEqualTo(2);
    }

    /** #56: an --only id that matches no order is said, not dropped silently. */
    @Test
    void applyOnlyWithAnUnknownIdSaysSo() throws IOException {
        Path app = project("packs: [logging]\n");
        Run r = run("apply", app.toString(), "--only", "FMT-000000");
        assertThat(r.out() + r.err()).contains("FMT-000000").contains("no such order");
    }

    /** #56: an extends: URL is pinned by #sha256=; unpinned is a warning naming the hash, a changed file an error. */
    @Test
    void extendsUrlsArePinned() throws IOException {
        String shared = "logging:\n  keyStyle: camel\n";
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/shared.yaml", ex -> {
            byte[] b = shared.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/shared.yaml";
            String hash = java.util.HexFormat.of().formatHex(sha256(shared));
            Run unpinned = run("policy", project("extends: [\"" + url + "\"]\n").toString(), "--check");
            assertThat(unpinned.out()).contains("extends: " + url + " is not pinned").contains(url + "#sha256=" + hash);
            Run pinned = run("policy", project("extends: [\"" + url + "#sha256=" + hash + "\"]\n").toString(), "--check");
            assertThat(pinned.exit()).as(pinned.out() + pinned.err()).isZero();
            Run changed = run("policy", project("extends: [\"" + url + "#sha256=00ff\"]\n").toString(), "--check");
            assertThat(changed.exit()).isEqualTo(2);
            assertThat(changed.err()).contains("policy " + url + " changed: its sha256 is " + hash);
        } finally {
            server.stop(0);
        }
    }

    private static byte[] sha256(String s) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** #5: a service built for a newer Java than logast runs on: scan says so (its newer APIs stay unresolved). */
    @Test
    void scanWarnsWhenTheServiceNeedsANewerJava() throws IOException {
        Path app = project("packs: [logging]\n");
        Files.writeString(app.resolve("build.gradle.kts"), "kotlin { jvmToolchain(99) }\n");
        assertThat(run("scan", app.toString(), "--format", "json").err())
                .contains("the service builds for Java 99, logast runs on Java " + Runtime.version().feature());
    }

    /** #24: logast.yaml pins a starter older than this logast's (dependency mode): said at load. */
    @Test
    void anOlderPinnedStarterIsAWarning() throws IOException {
        Path app = project("support:\n  coordinates: dev.logast:logging-support:0.0.9\n");
        Run r = run("policy", app.toString(), "--check");
        assertThat(r.out()).contains("support.coordinates: pins logging-support 0.0.9, but this logast (");
        assertThat(run("policy", project("support:\n  coordinates: dev.logast:logging-support:0.0.9\n  mode: vendor\n")
                .toString(), "--check").out()).as("vendor mode copies the classes: no pin to match").doesNotContain("pins logging-support");
    }

    /** #119: a Gradle build without a classpath file: apply stops and says how to get one, unless told to go on. */
    @Test
    void applyStopsWithoutAClasspathOnAGradleBuild() throws IOException {
        Path app = project("packs: [logging]\n");
        Files.writeString(app.resolve("build.gradle.kts"), "plugins { java }\n");
        Run r = run("apply", app.toString(), "--auto");
        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("no classpath yet (.logast/classpath*.txt): run `logast classpath .` first")
                .contains("often a JDK the build's toolchain asks for is not installed");
        assertThat(run("apply", app.toString(), "--auto", "--without-classpath").err()).doesNotContain("no classpath yet");
        Path plain = tmp.resolve("plain");   // its own folder: project() writes into the one above
        Files.createDirectories(plain.resolve("src/main/java/demo"));
        Files.writeString(plain.resolve("src/main/java/demo/Ok.java"), "package demo;\n\nclass Ok {\n}\n");
        assertThat(run("apply", plain.toString(), "--auto").err()).as("no Gradle build: as before").doesNotContain("no classpath yet");
    }
}
