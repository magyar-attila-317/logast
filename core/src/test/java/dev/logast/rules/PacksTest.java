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
        WorkOrder timeouts = r.orders().stream().filter(o -> o.rule().equals(Rules.HTTP_CLIENT_NO_TIMEOUT) && !o.edits().isEmpty())
                .findFirst().orElseThrow();
        assertThat(timeouts.edits().getFirst().text()).contains("connect-timeout: 5s").contains("read-timeout: 30s");

        WorkOrder leak = only(r, Rules.ERROR_DETAILS_EXPOSED, "application.yml");
        assertThat(leak.edits().getFirst().text()).contains("include-message: never").doesNotContain("always");

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
