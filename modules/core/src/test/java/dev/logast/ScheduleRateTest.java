package dev.logast;

import dev.logast.facts.Cron;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** #235: runs per day from a schedule. */
class ScheduleRateTest {
    @Test
    void aCronRunsAsOftenAsItsFieldsSayOverAYear() {
        assertThat(ScheduleRate.cronPerDay("0 */5 * * * *", Cron.Format.SPRING)).isEqualTo(288d);
        assertThat(ScheduleRate.cronPerDay("0 0 2 * * MON-FRI", Cron.Format.SPRING)).isCloseTo(5 / 7d, within(0.01));
        assertThat(ScheduleRate.cronPerDay("0 0 0 1 * *", Cron.Format.SPRING)).isCloseTo(12 / 365d, within(1e-9));
        assertThat(ScheduleRate.cronPerDay("-", Cron.Format.SPRING)).isNull();
        assertThat(ScheduleRate.cronPerDay("not a cron", Cron.Format.SPRING)).isNull();
    }

    @Test
    void aSweepRunsMoreOftenThanOnceAMinute() {   // #272
        assertThat(ScheduleRate.frequent(86_400_000d / 5_000)).as("fixedDelay = 5000").isTrue();
        assertThat(ScheduleRate.frequent(86_400_000d / 60_000)).as("once a minute: not yet").isFalse();
        assertThat(ScheduleRate.frequent(ScheduleRate.cronPerDay("0 */5 * * * *", Cron.Format.SPRING))).isFalse();
        assertThat(ScheduleRate.frequent(null)).as("rate unknown").isFalse();
    }

    @Test
    void placeholdersComeFromTheConfigurationElseTheirDefault() {
        assertThat(ScheduleRate.resolve("${jobs.sweep:0 0 * * * *}", Map.of())).isEqualTo("0 0 * * * *");
        assertThat(ScheduleRate.resolve("${jobs.sweep:0 0 * * * *}", Map.of("jobs.sweep", "0 */10 * * * *"))).isEqualTo("0 */10 * * * *");
        assertThat(ScheduleRate.resolve("${jobs.sweep}", Map.of())).isNull();
    }
}
