package dev.logast;

import dev.logast.orders.OrderJson;
import dev.logast.orders.ScanResult;
import dev.logast.orders.WorkOrder;
import dev.logast.policy.Policy;
import dev.logast.rules.Rules;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RulesTest {
    private static final Pattern EXPECT = Pattern.compile("// expect: ([A-Z_, ]+)");

    static ScanResult scan() {
        return Rules.scan(Fixtures.analysis());
    }

    /** "file:line RULE" for every rule an order fixes at its own position or at one of its locations. */
    static Set<String> findings(ScanResult r) {
        Set<String> out = new TreeSet<>();
        for (WorkOrder o : r.orders()) {
            Set<String> locationRules = new HashSet<>();
            for (WorkOrder.Location l : o.locations()) {
                out.add(l.file() + ":" + l.line() + " " + l.rule());
                locationRules.add(l.rule());
            }
            for (String rule : o.fixes()) {
                if (rule.equals(o.rule()) || !locationRules.contains(rule)) {
                    out.add(o.file() + ":" + o.line() + " " + rule);
                }
            }
        }
        return out;
    }

    static Set<String> expected(Path root) throws IOException {
        Set<String> out = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root.resolve("src/main/java"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(p);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = EXPECT.matcher(lines.get(i));
                    if (m.find()) {
                        for (String rule : m.group(1).split(",")) {
                            out.add(root.relativize(p).toString().replace('\\', '/') + ":" + (i + 1) + " " + rule.trim());
                        }
                    }
                }
            }
        }
        return out;
    }

    @Test
    void javaFindingsMatchMarkersExactly() throws IOException {
        ScanResult r = scan();
        System.out.println(OrderJson.MAPPER.writeValueAsString(r.orders()));
        Set<String> actual = new TreeSet<>(findings(r));
        actual.removeIf(s -> !s.startsWith("src/main/java"));
        Set<String> expected = expected(Fixtures.root());
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        Set<String> extra = new TreeSet<>(actual);
        extra.removeAll(expected);
        assertThat(missing).as("expected but not found").isEmpty();
        assertThat(extra).as("found but not expected").isEmpty();
    }

    @Test
    void projectSetupFindings() {
        ScanResult r = scan();
        assertThat(r.orders()).filteredOn(o -> o.rule().equals(Rules.SETUP_SUPPORT_STARTER)).singleElement()
                .satisfies(o -> {
                    assertThat(o.file()).isEqualTo("build.gradle.kts");
                    assertThat(o.edits()).singleElement().satisfies(e ->
                            assertThat(e.text()).contains("implementation(\"dev.logast:logging-support:"));
                });
        assertThat(r.orders()).filteredOn(o -> o.rule().equals(Rules.SETUP_OTEL_STARTER)).isEmpty();
        assertThat(r.orders()).filteredOn(o -> o.rule().equals(Rules.SETUP_LOGBACK_XML)).singleElement()
                .satisfies(o -> {
                    assertThat(o.edits()).extracting(e -> e.kind() + " " + e.file())
                            .containsExactly("delete src/main/resources/logback-spring.xml",
                                    "replace src/main/resources/application.yml");
                    assertThat(o.edits().get(1).text()).contains("com.example.shop: DEBUG");
                });
    }

    @Test
    void ordersDependOnStarter() {
        ScanResult r = scan();
        String sup = r.orders().stream().filter(o -> o.rule().equals(Rules.SETUP_SUPPORT_STARTER)).findFirst().orElseThrow().id();
        assertThat(r.orders()).filteredOn(o -> o.edits().stream().anyMatch(e -> e.text().contains("MdcScope")
                        || e.text().contains("ObservedContext") || e.text().contains("ContextExecutors")))
                .isNotEmpty().allSatisfy(o -> assertThat(o.dependsOn()).contains(sup));
    }

    private static WorkOrder at(ScanResult r, String fileSuffix, String rule) {
        return r.orders().stream().filter(o -> o.file().endsWith(fileSuffix) && o.fixes().contains(rule))
                .findFirst().orElseThrow(() -> new AssertionError("no " + rule + " in " + fileSuffix));
    }

    @Test
    void namedPlaceholderBecomesSuffixKey() {
        WorkOrder o = at(scan(), "FakeGateway.java", Rules.LOG_FORMAT);
        assertThat(o.slots()).isEmpty();
        assertThat(o.edits().getFirst().text()).isEqualTo("\"Fake charge accepted [amount={}]\", amountCents");
    }

    @Test
    void commentedSwallowIsLowSeverity() {
        assertThat(at(scan(), "DefaultOrderService.java", Rules.SWALLOWED_EXCEPTION).severity()).isEqualTo("low");
    }

    @Test
    void keepStyleModeOnlyFixesMechanicalProblems() {
        Policy p = Fixtures.policy();
        Policy keep = p.withLogging(new Policy.Logging(null, null, null, null, false, null));
        ScanResult r = Rules.scan(Analysis.run(Fixtures.root(), Fixtures.classpath(), keep));
        assertThat(r.orders()).noneMatch(o -> o.fixes().contains(Rules.LOG_FORMAT));
        assertThat(at(r, "DefaultOrderService.java", Rules.STRING_CONCAT_LOG).edits().getFirst().text())
                .isEqualTo("\"Placed order {}\", order.id()");
        assertThat(at(r, "PaymentClient.java", Rules.LOST_THROWABLE).edits().getFirst().text())
                .isEqualTo("\"Refund failed for order {}\", orderId, e");
        assertThat(at(r, "Mailer.java", Rules.PII_IN_LOG).reportOnly()).isTrue();
        assertThat(r.orders()).noneMatch(o -> !o.slots().isEmpty() && o.fixes().contains(Rules.LOG_FORMAT));
    }

    @Test
    void tracingAcrossProcessBoundaries() {
        ScanResult r = scan();
        assertThat(at(r, "ShippingClient.java", Rules.HTTP_CLIENT_UNTRACED).edits()).extracting(e -> e.text())
                .contains("restClientBuilder", "RestClient.Builder restClientBuilder");
        assertThat(at(r, "ClientConfig.java", Rules.HTTP_CLIENT_UNTRACED).edits()).extracting(e -> e.text())
                .contains("restTemplateBuilder.build()", "RestTemplateBuilder restTemplateBuilder");
        assertThat(at(r, "LegacyStockClient.java", Rules.HTTP_CLIENT_UNTRACED).reportOnly()).isTrue();
        WorkOrder kafka = at(r, "application.yml", Rules.KAFKA_OBSERVATION_DISABLED);
        assertThat(kafka.edits().getFirst().text()).contains("template:", "listener:", "observation-enabled: true");
        // the listener is not wrapped in an ObservedContext: switching on listener observation covers it
        assertThat(r.orders()).noneMatch(o -> o.file().endsWith("ShipmentPublisher.java") && o.fixes().contains(Rules.ENTRYPOINT_NOT_OBSERVED));
        assertThat(r.orders()).filteredOn(o -> o.file().endsWith("JobLauncher.java")).extracting(o -> o.edits().getFirst().text())
                .anySatisfy(t -> assertThat(t).contains("pb.environment().putAll(TraceContextEnv.current());"))
                .anySatisfy(t -> assertThat(t).contains(".addAllToEnv(TraceContextEnv.current()"));
    }

    @Test
    void metricsPackIsSelectableOnItsOwn() {
        WorkOrder naming = at(scan(), "OrderMetrics.java", Rules.METRIC_NAMING);
        assertThat(naming.edits().getFirst().text()).isEqualTo("\"orders.placed\""); // dotted + glossary (purchase -> order)
        ScanResult metricsOnly = Rules.scan(Analysis.run(Fixtures.root(), Fixtures.classpath(), Fixtures.policy().withPacks(java.util.List.of("metrics"))));
        assertThat(metricsOnly.orders()).extracting(WorkOrder::rule)
                .allMatch(r -> Rules.packsOf(r).contains("metrics"));
        assertThat(metricsOnly.orders()).extracting(WorkOrder::rule).contains(Rules.METRIC_NAMING, Rules.METRIC_HIGH_CARDINALITY_TAG);
    }
}
