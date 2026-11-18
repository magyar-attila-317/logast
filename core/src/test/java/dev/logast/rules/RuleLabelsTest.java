package dev.logast.rules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** #101: the human summary names every rule in plain words. */
class RuleLabelsTest {
    @Test
    void everyRuleHasAShortLabel() {
        for (String rule : Rules.all()) {
            assertThat(RuleLabels.has(rule)).as(rule + " has no label in RuleLabels").isTrue();
            assertThat(RuleLabels.of(rule)).as(rule).hasSizeLessThanOrEqualTo(40).doesNotContain("_");
        }
    }
}
