package dev.logast;

import dev.logast.apply.Applier;
import dev.logast.apply.Fill;
import dev.logast.apply.FillValidator;
import dev.logast.orders.ScanResult;
import dev.logast.orders.WorkOrder;
import dev.logast.policy.Policy;
import dev.logast.rules.DomainValues;
import dev.logast.rules.Glossary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** scan → fill → apply until nothing applicable is left, then the result must compile. */
class RoundTripTest {
    @TempDir
    Path tmp;

    private ScanResult scan(Path root) {
        return Logast.scan(root, Fixtures.classpath(), Logast.policy(root, null));
    }

    /** Fills every slot with neutral text and applies until no order with edits is left (max 4 passes). */
    private List<Applier.Result> converge(Path root) {
        Policy policy = Logast.policy(root, null);
        Applier applier = new Applier(this::scan, new FillValidator(policy));
        List<Applier.Result> results = new ArrayList<>();
        ScanResult last = scan(root);
        for (int pass = 0; pass < 4 && last.orders().stream().anyMatch(o -> !o.reportOnly()); pass++) {
            Map<String, Fill> fills = new LinkedHashMap<>();
            for (WorkOrder o : last.orders()) {
                if (!o.slots().isEmpty()) {
                    Map<String, String> text = new LinkedHashMap<>();
                    o.slots().forEach((k, slot) -> text.put(k, slot.pattern() == null
                            ? "Generated message for " + o.rule().toLowerCase()
                            : slot.pattern().replace("{subject}", "Generated " + o.rule().toLowerCase().replace('_', ' '))));
                    fills.put(o.id(), Fill.of(text, List.of()));
                }
            }
            Applier.Result r = applier.apply(root, new Applier.Selection(true, Set.of(), fills), true);
            System.out.println("pass " + pass + ": applied=" + r.applied() + " rejected=" + r.rejected() + " rounds=" + r.rounds());
            results.add(r);
            last = scan(root);
        }
        assertThat(results.getFirst().rejected()).isEmpty();
        assertThat(last.orders()).as("only report-only orders remain").allMatch(WorkOrder::reportOnly);
        // after the swallowed exception in the caller logs, log-and-rethrow becomes a proven "logged twice"
        assertThat(last.orders()).extracting(WorkOrder::rule)
                .isSubsetOf("LOG_AND_RETHROW", "EXCEPTION_LOGGED_TWICE", "ASYNC_CONTEXT_LOSS", "AREA_MDC_MISSING",
                        "HTTP_CLIENT_UNTRACED", "METRIC_HIGH_CARDINALITY_TAG", "METRIC_DIRECT_REGISTRY",
                        "METRIC_TAG_KEYS_MISMATCH", "METRIC_GAUGE_WEAK_REF", "METRIC_REGISTERED_PER_CALL");  // report-only
        assertThat(last.orders()).filteredOn(o -> o.rule().equals("ASYNC_CONTEXT_LOSS"))
                .singleElement().satisfies(o -> assertThat(o.title()).startsWith("Parallel stream"));
        return results;
    }

    @Test
    void fixtureConvergesAndCompiles() throws IOException {
        Path root = tmp.resolve("app");
        copy(Fixtures.root(), root);
        // a tool's daemon socket in the project (e.g. .codegraph/daemon.sock) must not break the scratch copy
        Files.createDirectories(root.resolve(".codegraph"));
        try (var server = java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
            server.bind(java.net.UnixDomainSocketAddress.of(root.resolve(".codegraph/daemon.sock")));
            List<Applier.Result> results = converge(root);
            System.out.println(results.getFirst().diff());
        }
        assertThat(root.resolve("src/main/resources/logback-spring.xml")).doesNotExist();
        assertThat(Files.readString(root.resolve("build.gradle.kts"))).contains("dev.logast:logging-support");
        assertThat(Files.readString(root.resolve("src/main/resources/application.yml"))).contains("com.example.shop: DEBUG");
        compile(root, true);
    }

    @Test
    void vendorModeCompilesWithoutTheLibrary() throws IOException {
        Path root = tmp.resolve("vendored");
        copy(Fixtures.root(), root);
        Files.writeString(root.resolve("logast.yaml"), Files.readString(root.resolve("logast.yaml")) + "support:\n  mode: vendor\n");
        converge(root);
        Path pkg = root.resolve("src/main/java/com/example/shop/logging");
        assertThat(pkg.resolve("ObservedContext.java")).exists();
        assertThat(Files.readString(pkg.resolve("LoggingSupportAutoConfiguration.java")))
                .contains("package com.example.shop.logging;", "@Configuration(proxyBeanMethods = false)")
                .doesNotContain("@AutoConfiguration");
        assertThat(Files.readString(root.resolve("src/main/resources/META-INF/spring.factories")))
                .contains("com.example.shop.logging.LoggingDefaultsEnvironmentPostProcessor");
        assertThat(Files.readString(root.resolve("build.gradle.kts"))).doesNotContain("logging-support");
        assertThat(Files.readString(root.resolve("src/main/java/com/example/shop/web/OrderController.java")))
                .contains("import com.example.shop.logging.MdcScope;");
        compile(root, false);
    }

    private static void compile(Path root, boolean withSupportLibrary) throws IOException {
        List<String> sources;
        try (Stream<Path> s = Files.walk(root.resolve("src/main/java"))) {
            sources = s.filter(p -> p.toString().endsWith(".java")).map(Path::toString).toList();
        }
        List<String> cp = new ArrayList<>(Fixtures.classpath().stream().map(Path::toString).toList());
        if (withSupportLibrary) {
            cp.add(System.getProperty("logast.support.classes"));
        }
        List<String> args = new ArrayList<>(List.of("-d", root.resolve("out").toString(), "-proc:none",
                "-cp", String.join(java.io.File.pathSeparator, cp)));
        args.addAll(sources);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = javac.run(null, null, err, args.toArray(String[]::new));
        assertThat(rc).as(err.toString()).isZero();
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path rel = from.relativize(p);
                if (rel.startsWith("build") || rel.startsWith(".gradle")) {
                    continue;
                }
                Path dst = to.resolve(rel.toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dst);
                } else {
                    Files.copy(p, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
