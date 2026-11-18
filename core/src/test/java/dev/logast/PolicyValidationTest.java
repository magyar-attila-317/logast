package dev.logast;

import dev.logast.policy.Policy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #56: a policy that parses but means nothing (or something wrong) fails at load time with the file, the YAML path and
 * the fix; one that is probably wrong but harmless loads with a warning. The policies in the repo load clean.
 */
class PolicyValidationTest {
    @TempDir
    Path dir;

    private Policy load(String yaml) throws IOException {
        Files.writeString(dir.resolve("logast.yaml"), yaml);
        return Policy.resolve(dir, null);
    }

    private String error(String yaml) {
        Throwable t = org.assertj.core.api.Assertions.catchThrowable(() -> load(yaml));
        assertThat(t).as("loading must fail").isInstanceOf(IllegalArgumentException.class);
        return t.getMessage();
    }

    // ---- the policies in the repo ---------------------------------------------------------------

    @Test
    void repoPoliciesLoadWithoutWarnings() throws IOException {
        Path repo = Path.of("").toAbsolutePath();
        while (!Files.exists(repo.resolve("settings.gradle.kts"))) {
            repo = repo.getParent();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> e2e = Files.list(repo.resolve("e2e"))) {
            e2e.flatMap(d -> Stream.of(d.resolve("logast.yml"), d.resolve("logast.yaml"))).filter(Files::exists).forEach(files::add);
        }
        files.add(repo.resolve("fixtures/sample-app/logast.yaml"));
        files.add(repo.resolve("logast.example.yaml"));
        files.add(repo.resolve("examples/restaurant-company.yaml"));
        files.add(repo.resolve("examples/restaurant-service.yaml"));
        assertThat(files).as("e2e scenarios found").hasSizeGreaterThan(10);
        assertThat(Policy.defaults().warnings()).isEmpty();
        for (Path f : files) {
            assertThat(Policy.load(f).warnings()).as(f.toString()).isEmpty();
        }
    }

    // ---- errors: the setting cannot mean anything ------------------------------------------------

    @Test
    void unknownRuleIdIsAnErrorWithASuggestion() {
        assertThat(error("rules:\n  LOG_FORAMT: { enabled: false }\n"))
                .contains("logast.yaml: rules.LOG_FORAMT: unknown rule id; did you mean LOG_FORMAT?");
    }

    @Test
    void unknownSeverityIsAnError() {
        assertThat(error("rules:\n  LOG_FORMAT: { severity: critical }\n"))
                .contains("logast.yaml: rules.LOG_FORMAT.severity: 'critical' is not a severity; use one of [high, medium, low]");
    }

    /** #24: a pinned starter older than the calls this logast writes; newer, equal or non-numeric pins say nothing. */
    @Test
    void anOlderPinnedStarterIsNamed() {
        assertThat(Policy.Support.olderStarter("0.1.0", "0.2.0")).startsWith("pins logging-support 0.1.0, but this logast (0.2.0)")
                .endsWith("or use support.mode: vendor");
        assertThat(Policy.Support.olderStarter("0.9.3", "1.0.0")).isNotNull();
        assertThat(Policy.Support.olderStarter("0.2.0", "0.2.0")).isNull();
        assertThat(Policy.Support.olderStarter("0.3.0", "0.2.0")).isNull();
        assertThat(Policy.Support.olderStarter("latest", "0.2.0")).isNull();
    }

    @Test
    void queriesBlockIsChecked() {
        assertThat(error("queries:\n  backends: [prometheus, splunck]\n"))
                .contains("logast.yaml: queries.backends: 'splunck' is not a query backend; use any of [prometheus, newrelic, "
                        + "splunk]; did you mean splunk?");
        assertThat(error("queries:\n  splunk:\n    index: App Logs\n"))
                .contains("logast.yaml: queries.splunk.index: 'App Logs' is not a Splunk index name");
        assertThat(error("queries:\n  prometheus:\n    serviceLabel: service.name\n"))
                .contains("logast.yaml: queries.prometheus.serviceLabel: 'service.name' is not a Prometheus label name");
    }

    @Test
    void unknownPackIsAnError() {
        assertThat(error("packs: [loging, tracing]\n"))
                .contains("logast.yaml: packs[0]: unknown pack 'loging', it selects no rule; did you mean logging?");
    }

    @Test
    void unknownPackOnTheCommandLineIsAnError() {
        assertThatThrownBy(() -> Policy.defaults().withPacks(List.of("logging", "metric")))
                .hasMessageContaining("--pack metric: unknown pack; did you mean metrics?");
    }

    @Test
    void regexesThatDoNotCompileAreErrors() {
        String m = error("""
                logging:
                  protectedMessages: ["^ALERT(:"]
                  vagueMessages: ["[unclosed"]
                audit:
                  actions:
                    - method: "(?<bad"
                """);
        assertThat(m).contains("logast.yaml: logging.protectedMessages[0]: invalid regex")
                .contains("logast.yaml: logging.vagueMessages[5]: invalid regex")   // after the 5 built-in ones
                .contains("logast.yaml: audit.actions[4].method: invalid regex");   // after the 4 built-in actions
    }

    @Test
    void unknownLevelsAreErrors() {
        String m = error("""
                logging:
                  swallowedLevel: warning
                  minEntrypointLevel: verbose
                levels:
                  clientErrorLevel: notice
                """);
        assertThat(m).contains("logast.yaml: logging.swallowedLevel: 'warning' is not a log level; use one of "
                        + "[trace, debug, info, warn, error]; did you mean warn?")
                .contains("logging.minEntrypointLevel: 'verbose' is not a log level")
                .contains("levels.clientErrorLevel: 'notice' is not a log level");
    }

    @Test
    void loggerNameMustBeAJavaIdentifier() {
        assertThat(error("logging:\n  loggerName: my-log\n"))
                .contains("logast.yaml: logging.loggerName: 'my-log' is not a Java identifier");
    }

    @Test
    void unknownStructuredFormatIsAnError() {
        assertThat(error("logging:\n  structuredFormat: logstsh\n"))
                .contains("logging.structuredFormat: 'logstsh' is not a structured log format")
                .contains("did you mean logstash?");
    }

    @Test
    void customStructuredFormatterClassIsAccepted() throws IOException {
        assertThat(load("logging:\n  structuredFormat: com.example.logging.MyFormatter\n").warnings()).isEmpty();
    }

    @Test
    void auditActionWithoutAFieldAndBadEmittersAreErrors() {
        String m = error("""
                audit:
                  emitters: [AuditService, com.example.ActivityLogger#log, com.example.Audit#]
                  actions:
                    - {}
                """);
        assertThat(m).contains("logast.yaml: audit.actions[4]: matches no entrypoint: give http, method or term")
                .contains("audit.emitters[0]: 'AuditService' is not a fully qualified type")
                .contains("audit.emitters[2]: 'com.example.Audit#' is not a fully qualified type")
                .doesNotContain("audit.emitters[1]");
    }

    @Test
    void durationsMustBeSpringDurations() throws IOException {
        assertThat(error("resilience:\n  httpReadTimeout: 30 seconds\n  dbQueryTimeout: PT5X\n"))
                .contains("logast.yaml: resilience.httpReadTimeout: '30 seconds' is not a Spring duration")
                .contains("resilience.dbQueryTimeout: 'PT5X' is not a Spring duration")
                .doesNotContain("httpConnectTimeout");
        assertThat(load("resilience:\n  httpConnectTimeout: 500ms\n  httpReadTimeout: PT30S\n  dbQueryTimeout: 120000\n")
                .warnings()).isEmpty();
    }

    @Test
    void contradictingVocabularyIsAnError() {
        String m = error("""
                vocabulary:
                  order:
                    aliases: [purchase, customer]
                    mdc: { key: orderId, aliases: [customerId] }
                  customer:
                    aliases: [client, purchase]
                    mdc: { key: customerId, cardinality: hihg }
                """);
        assertThat(m).contains("logast.yaml: vocabulary.order.aliases[1]: 'customer' is itself a vocabulary term")
                .contains("vocabulary.customer.aliases[1]: 'purchase' is already an alias of 'order'")
                .contains("vocabulary.order.mdc.aliases[0]: 'customerId' is the MDC key of 'customer'")
                .contains("vocabulary.customer.mdc.cardinality: 'hihg' is not high or low");
    }

    @Test
    void anAreaMayReuseAGlobalTermAsItsOwnAlias() throws IOException {
        Policy p = load("""
                vocabulary:
                  order: {}
                areas:
                  kitchen:
                    packages: [com.x.kitchen]
                    vocabulary:
                      ticket: { aliases: [order] }
                """);
        assertThat(p.warnings()).isEmpty();
    }

    @Test
    void otherOutOfRangeValuesAreErrors() {
        String m = error("""
                support:
                  coordinates: com.example:logging-support
                mdc:
                  extractRepeated: -1
                levels:
                  boundedLoopMax: -5
                """);
        assertThat(m).contains("support.coordinates: 'com.example:logging-support' is not group:artifact:version")
                .contains("mdc.extractRepeated: must be 0 (off) or more, was -1")
                .contains("levels.boundedLoopMax: must be 0 or more, was -5");
    }

    @Test
    void anErrorNamesTheLayerThatSetIt() throws IOException {
        Files.writeString(dir.resolve("company.yaml"), "logging:\n  vagueMessages: [\"(oops\"]\n");
        Files.writeString(dir.resolve("logast.yaml"), "extends: [company.yaml]\nlogging:\n  vagueMessages: [\"^fine$\"]\n"
                + "rules:\n  LOG_FORMAT: { enabled: false }\n");
        assertThatThrownBy(() -> Policy.resolve(dir, null))
                .hasMessageContaining("company.yaml: logging.vagueMessages[5]: invalid regex")
                .hasMessageNotContaining("vagueMessages[6]");
    }

    // ---- warnings: probably wrong, no effect ----------------------------------------------------

    @Test
    void settingsWithoutEffectAreWarnings() throws IOException {
        Policy p = load("""
                levels:
                  noInfoInLoops: true
                  infoInLoops: always
                metrics:
                  catalogClass: com.example.metrics.BusinessMetrics
                vocabulary:
                  pledge:
                    logKey: pledgeCode
                areas:
                  payments:
                    requiredMdc: [orderId]
                """);
        assertThat(p.warnings()).containsExactlyInAnyOrder(
                "logast.yaml: levels.noInfoInLoops: true overrides levels.infoInLoops: always (it means infoInLoops: "
                        + "never); keep only one of them",
                "logast.yaml: metrics.catalogClass: is only used with metrics.catalog: single (catalog is per-package), "
                        + "so it has no effect",
                "logast.yaml: vocabulary.pledge.logKey: has no effect without vocabulary.pledge.mdc.key",
                "logast.yaml: areas.payments.packages: is empty: no class belongs to area 'payments', so its vocabulary "
                        + "and requiredMdc apply nowhere");
    }

    @Test
    void noInfoInLoopsAloneIsNoWarning() throws IOException {
        assertThat(load("levels:\n  noInfoInLoops: true\n").warnings()).isEmpty();
    }
}
