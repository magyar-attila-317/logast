package dev.logast.rules;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** #142: the pack pages' rule tables, as the fleet report's rule help. */
class RuleHelpTest {
    @Test
    void everyRuleHasWhatItFindsAndItsFixFromThePackPages() {
        List<String> missing = Rules.all().stream().filter(r -> !r.startsWith("RUNTIME_"))
                .filter(r -> RuleHelp.all().get(r) == null || RuleHelp.all().get(r).fix() == null).toList();
        assertThat(missing).as("rules without a row on a pack page").isEmpty();
        RuleHelp.Help db = RuleHelp.of(Rules.DB_NO_TIMEOUT);
        assertThat(db.label()).isEqualTo("transactions without a timeout");
        assertThat(db.severity()).isEqualTo("high");
        assertThat(db.fix()).startsWith("AUTO: spring.jdbc.template.query-timeout").doesNotContain("`");
    }

    @Test
    void markdownBecomesPlainText() {
        assertThat(RuleHelp.plain("see [the starter](../starter.md), set `a.b` **now**")).isEqualTo("see the starter, set a.b now");
    }
}
