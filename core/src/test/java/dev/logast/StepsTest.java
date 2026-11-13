package dev.logast;

import dev.logast.rules.Rules;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

/** Every rule belongs to exactly one pipeline step (docs/pipeline-plan.md). */
class StepsTest {
    @Test
    void everyRuleHasAStep() {
        for (String rule : Rules.all()) {
            assertThatCode(() -> Rules.stepOf(rule)).as(rule).doesNotThrowAnyException();
        }
    }
}
