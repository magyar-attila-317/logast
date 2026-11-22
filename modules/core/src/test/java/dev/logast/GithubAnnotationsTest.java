package dev.logast;

import dev.logast.orders.GithubAnnotations;
import dev.logast.orders.WorkOrder;
import dev.logast.rules.Rules;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** #148: findings as GitHub Actions workflow commands, and the step summary. */
class GithubAnnotationsTest {
    @Test
    void oneEscapedCommandPerFindingByItsSeverity() {
        List<WorkOrder> orders = Rules.scan(Fixtures.analysis()).orders();
        String out = GithubAnnotations.of(orders, List.of("no classpath: types unresolved"));
        List<String> lines = out.lines().toList();
        assertThat(lines).hasSize(orders.size() + 1);
        WorkOrder high = orders.stream().filter(o -> o.severity().equals("high")).findFirst().orElseThrow();
        assertThat(out).contains("::error file=" + high.file().replace(":", "%3A") + ",line=" + Math.max(1, high.line())
                + ",title=logast " + high.rule() + " (");
        assertThat(lines).as("one line each: newlines inside a message are escaped").allMatch(l -> l.startsWith("::"));
        assertThat(lines.getLast()).as("':' needs no escaping in a message, only in properties")
                .isEqualTo("::warning title=logast::analysis incomplete: no classpath: types unresolved");
        if (orders.stream().anyMatch(o -> o.severity().equals("low"))) {
            assertThat(out).contains("::notice file=");
        }
    }

    @Test
    void summaryCountsBySeverityAndRuleInWords() {
        List<WorkOrder> orders = Rules.scan(Fixtures.analysis()).orders();
        String md = GithubAnnotations.summary(orders, List.of());
        assertThat(md).startsWith("### logast\n\n**" + orders.size() + " findings**: ").contains("| rule | what | findings |",
                "| `" + orders.getFirst().rule() + "` | ");
        assertThat(GithubAnnotations.summary(List.of(), List.of("x"))).contains("No findings.", "**Analysis incomplete**: x");
    }
}
