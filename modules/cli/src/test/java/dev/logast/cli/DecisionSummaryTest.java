package dev.logast.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** #328: an apply's judgement calls read from its diff. */
class DecisionSummaryTest {
    @Test
    void countsLinesExceptionsAndMetricNames() {
        String diff = """
                diff --git a/src/A.java b/src/A.java
                --- a/src/A.java
                +++ b/src/A.java
                @@ -1,4 +1,6 @@
                         } catch (IllegalStateException e) {
                +            log.debug("{{MSG}} [errorType={}]", e.getClass().getSimpleName());
                             return false;
                -            log.warn("Read failed");
                +            log.warn("Read failed", e);
                +            log.warn("Sync failed", e);
                +        Outcomes.count("order.ship", "failed");
                +        Outcomes.countKind("broadcast", "table.created", "failed");
                +        Outcomes.countKind("broadcast", "preset.updated", "failed");
                +        Outcomes.count("outcome", "completed");
                """;
        String vendored = """
                diff --git a/src/logging/Outcomes.java b/src/logging/Outcomes.java
                +// Copied by logast (support.mode: vendor) from logging-support. Keep in sync or switch to the starter.
                + * Outcomes.count("pledges", "used");
                """;
        String s = DecisionSummary.of("demo", diff + vendored);
        assertThat(s).as("the copied support classes are left out").doesNotContain("pledges");
        assertThat(s).contains("  debug with [errorType]: 1\n", "  warn with the exception: 2\n")
                .contains("existing lines given the exception: 1\n")
                .contains("metrics: 3 names, 2 of one word, 0 of more than three\n")
                .contains("  family broadcast [preset.updated, table.created]\n", "  outcome\n", "  order.ship\n");
    }
}
