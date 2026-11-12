package dev.logast.cli;

import dev.logast.Analysis;
import dev.logast.Logast;
import dev.logast.orders.CompactFormat;
import dev.logast.orders.OrderJson;
import dev.logast.orders.ScanResult;
import dev.logast.rules.RuntimeVerifier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

@Command(name = "verify", description = "Run the existing tests with log capture and check what they actually logged "
        + "(context lost across threads, null values, coverage of log statements). The service's build is not changed.")
final class Verify extends Main.Common implements Callable<Integer> {
    @Option(names = "--gradle-dir", description = "Where to run Gradle (default: the root; use the repo root for a subproject)")
    Path gradleDir;
    @Option(names = "--task", defaultValue = "test", description = "Gradle task(s) to run, e.g. :app:test")
    String task;
    @Option(names = "--no-run", description = "Only analyse captures from a previous run")
    boolean noRun;
    @Option(names = "--format", defaultValue = "compact", description = "compact | json")
    String format;

    @Override
    public Integer call() throws IOException, InterruptedException {
        Path root = root();
        if (!noRun) {
            int rc = runTests(root);
            System.err.println("tests finished (gradle exit " + rc + "; failures do not stop verification)");
        }
        List<Path> captures;
        try (Stream<Path> s = Files.walk(root, 5)) {
            captures = s.filter(p -> p.endsWith(Path.of("build", "logast", "test-logs.jsonl"))).toList();
        }
        if (captures.isEmpty()) {
            System.err.println("no captured logs under " + root + " (did the tests run? is Logback the logging backend?)");
            return 3;
        }
        Analysis a = Logast.analyse(root, classpath(), policy());
        RuntimeVerifier.Result r = RuntimeVerifier.verify(a, RuntimeVerifier.read(captures));
        ScanResult out = new ScanResult(1, root.toString(), a.stats(), Map.of(), r.orders());
        Files.createDirectories(root.resolve(".logast"));
        OrderJson.MAPPER.writeValue(root.resolve(".logast/verify.json").toFile(), Map.of("result", out,
                "events", r.events(), "logStatements", r.logStatements(), "exercised", r.exercised(), "unmapped", r.unmapped()));
        if (format.equals("json")) {
            System.out.println(OrderJson.MAPPER.writeValueAsString(r));
            return r.orders().isEmpty() ? 0 : 1;
        }
        System.out.printf("verify: %d log events from tests; %d of %d log statements exercised (%d%%)%n", r.events(),
                r.exercised(), r.logStatements(), r.logStatements() == 0 ? 0 : 100 * r.exercised() / r.logStatements());
        if (r.unmapped() > 0) {
            System.out.println("events from project classes that match no known log statement: " + r.unmapped() + " e.g. " + r.unmappedSample());
        }
        System.out.print(CompactFormat.render(out, r.orders(), List.of()));
        return r.orders().isEmpty() ? 0 : 1;
    }

    private int runTests(Path root) throws IOException, InterruptedException {
        Path jar = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(Path::of).filter(p -> p.getFileName().toString().startsWith("test-capture")).findFirst()
                .orElseThrow(() -> new IllegalStateException("test-capture jar not found on the logast classpath"));
        Path init = Files.createTempFile("logast-verify", ".init.gradle");
        Files.writeString(init, """
                allprojects {
                    plugins.withId('java') {
                        dependencies { testRuntimeOnly files('%s') }
                        tasks.withType(Test).configureEach {
                            notCompatibleWithConfigurationCache('logast verify')
                            def capture = project.layout.buildDirectory.file('logast/test-logs.jsonl').get().asFile
                            systemProperty 'logast.capture.file', capture.absolutePath
                            outputs.upToDateWhen { false }
                            doFirst { capture.delete() }
                        }
                    }
                }
                """.formatted(jar.toAbsolutePath().toString().replace("\\", "/")));
        Path dir = gradleDir != null ? gradleDir.toAbsolutePath() : root;
        Path wrapper = dir.resolve("gradlew");
        String gradle = Files.exists(wrapper) ? wrapper.toString() : "gradle";
        Process p = new ProcessBuilder(gradle, "-q", "--no-configuration-cache", "--continue", "--init-script", init.toString(), task)
                .directory(dir.toFile()).inheritIO().start();
        int rc = p.waitFor();
        Files.deleteIfExists(init);
        return rc;
    }
}
