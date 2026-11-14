package dev.logast.apply;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** metrics.json decisions survive an id change of their outcome; ambiguous cases are never guessed. */
class MetricsFileTest {
    private static MetricsFile.Decision counter(String file, String method, String event) {
        return new MetricsFile.Decision("counter", "", "", file, method, event);
    }

    @Test
    void aDecisionUnderAnOldIdMovesToTheOutcomesCurrentId() {
        var d = counter("src/A.java", "A.run", "completed");
        var out = MetricsFile.match(Map.of("OUM-old", d), List.of(new MetricsFile.Spot("OUM-new", "src/A.java", "A.run", "completed")));
        assertThat(out).containsOnlyKeys("OUM-new").containsEntry("OUM-new", d);
    }

    @Test
    void twoCandidatesForOneDecisionAreNotGuessed() {
        var out = MetricsFile.match(Map.of("OUM-old", counter("src/A.java", "A.run", "completed")), List.of(
                new MetricsFile.Spot("OUM-1", "src/A.java", "A.run", "completed"),
                new MetricsFile.Spot("OUM-2", "src/A.java", "A.run", "completed")));
        assertThat(out).isEmpty();
    }

    @Test
    void aDecisionWhoseIdStillExistsStaysAndOthersDoNotTakeItsPlace() {
        var kept = counter("src/A.java", "A.run", "completed");
        var out = MetricsFile.match(Map.of("OUM-1", kept, "OUM-old", counter("src/B.java", "B.run", "failed")),
                List.of(new MetricsFile.Spot("OUM-1", "src/A.java", "A.run", "completed"),
                        new MetricsFile.Spot("OUM-2", "src/A.java", "A.run", "completed")));
        assertThat(out).containsOnlyKeys("OUM-1");
    }
}
