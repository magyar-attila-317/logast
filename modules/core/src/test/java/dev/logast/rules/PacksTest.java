package dev.logast.rules;

import dev.logast.Fixtures;
import dev.logast.orders.ScanResult;
import dev.logast.orders.WorkOrder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** resilience / errors / audit packs: the findings anchored in YAML, and the helpers behind them. */
class PacksTest {
    private static ScanResult scan() {
        return Rules.scan(Fixtures.analysis());
    }

    private static WorkOrder only(ScanResult r, String rule, String fileSuffix) {
        return r.orders().stream().filter(o -> o.rule().equals(rule) && o.file().endsWith(fileSuffix)).findFirst().orElseThrow();
    }

    @Test
    void yamlFixes() {
        ScanResult r = scan();
        // RestClient.builder() is not the builder Spring Boot configures: spring.http.clients.* would not reach it (#48),
        // so it is reported where it is built and no property is set for it (the YAML fix is in e2e/expected)
        WorkOrder timeouts = only(r, Rules.HTTP_CLIENT_NO_TIMEOUT, "ShippingClient.java");
        assertThat(timeouts.reportOnly()).isTrue();
        assertThat(timeouts.title()).contains("built from RestClient.builder()");
        assertThat(r.orders()).noneMatch(o -> o.rule().equals(Rules.HTTP_CLIENT_NO_TIMEOUT) && !o.edits().isEmpty());

        WorkOrder leak = only(r, Rules.ERROR_DETAILS_EXPOSED, "application.yml");
        // include-message changes the error body clients may read: a decision, not an automatic fix (#47)
        assertThat(leak.edits()).isEmpty();
        assertThat(leak.reportOnly()).isTrue();
        assertThat(leak.suggestion()).contains("Decide on include-message");

        WorkOrder problem = only(r, Rules.PROBLEM_DETAILS_DISABLED, "application.yml");
        assertThat(problem.reportOnly()).isTrue();   // changes the API's error body: a decision, not a mechanical fix
    }

    @Test
    void mergeOverwritesAndDoesNotCopyComments() {
        String before = "server:\n  error:\n    include-message: always   # old comment\n";
        String kept = SetupRules.mergeYaml(before, "logging:\n  level:\n    root: INFO\n");
        assertThat(kept).contains("include-message: always   # old comment").contains("root: INFO\n")
                .doesNotContain("root: INFO   # old comment");
        String changed = SetupRules.mergeYaml(before, "server:\n  error:\n    include-message: never\n", true);
        assertThat(changed).contains("include-message: never").doesNotContain("always");
    }

    @Test
    void auditGlobs() {
        assertThat(AuditRules.glob("DELETE **").matcher("DELETE /orders/{orderId}").matches()).isTrue();
        assertThat(AuditRules.glob("* /admin/**").matcher("POST /admin/customers/{id}/reset").matches()).isTrue();
        assertThat(AuditRules.glob("* /admin/**").matcher("POST /orders/admin").matches()).isFalse();
    }
}
