package dev.logast.kb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #159: cron expressions and what runs when. */
class CronTest {
    @TempDir
    Path tmp;

    private static final ZoneId UTC = ZoneOffset.UTC;

    private static List<String> on(String expr, Cron.Format f, String day) {
        return Cron.parse(expr, f).on(LocalDate.parse(day), UTC).stream().map(t -> t.toLocalTime().toString()).toList();
    }

    @Test
    void unixAndSpringFields() {
        assertThat(on("30 5 * * *", Cron.Format.UNIX, "2026-11-23")).containsExactly("05:30");
        assertThat(on("0 30 5 * * *", Cron.Format.SPRING, "2026-11-23")).as("seconds first").containsExactly("05:30");
        assertThat(on("*/20 9-10 * * MON-FRI", Cron.Format.UNIX, "2026-11-23")).as("a Monday")
                .containsExactly("09:00", "09:20", "09:40", "10:00", "10:20", "10:40");
        assertThat(on("*/20 9-10 * * MON-FRI", Cron.Format.UNIX, "2026-11-22")).as("a Sunday").isEmpty();
        assertThat(on("0 0 * * 7", Cron.Format.UNIX, "2026-11-22")).as("7 is Sunday too").containsExactly("00:00");
        assertThat(on("0 12 * JAN,NOV *", Cron.Format.UNIX, "2026-11-23")).containsExactly("12:00");
        assertThat(on("0 12 * DEC *", Cron.Format.UNIX, "2026-11-23")).isEmpty();
        assertThat(on("5/30 0 * * *", Cron.Format.UNIX, "2026-11-23")).as("a start with a step").containsExactly("00:05", "00:35");
        assertThat(on("@daily", Cron.Format.UNIX, "2026-11-23")).containsExactly("00:00");
        assertThat(on("@hourly", Cron.Format.SPRING, "2026-11-23")).hasSize(24);
        assertThat(on("0 0 2 ? * MON", Cron.Format.SPRING, "2026-11-23")).containsExactly("02:00");
    }

    @Test
    void dayOfMonthWithDayOfWeek() {
        // 2026-11-23 is a Monday, not the 1st
        assertThat(on("0 6 1 * MON", Cron.Format.UNIX, "2026-11-23")).as("unix: either day field").containsExactly("06:00");
        assertThat(on("0 0 6 1 * MON", Cron.Format.SPRING, "2026-11-23")).as("spring: both").isEmpty();
        assertThat(on("0 0 31 * *", Cron.Format.UNIX, "2026-11-30")).as("no 31st in November").isEmpty();
    }

    @Test
    void daylightSavingTime() {
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        assertThat(Cron.parse("30 2 * * *", Cron.Format.UNIX).on(LocalDate.parse("2026-03-29"), berlin)).as("02:30 does not exist").isEmpty();
        assertThat(Cron.parse("30 2 * * *", Cron.Format.UNIX).on(LocalDate.parse("2026-10-25"), berlin)).as("02:30 twice, runs once").hasSize(1);
        ZonedDateTime from = ZonedDateTime.parse("2026-11-20T10:00:00Z");
        assertThat(Cron.parse("0 3 1 * *", Cron.Format.UNIX).next(from, 2)).extracting(ZonedDateTime::toString)
                .containsExactly("2026-12-01T03:00Z", "2027-01-01T03:00Z");
    }

    /** #178: intervals as @Scheduled reads them, readable. */
    @Test
    void intervals() {
        assertThat(Schedules.interval("300000", null)).isEqualTo("5 min");
        assertThat(Schedules.interval("60_000", null)).isEqualTo("1 min");
        assertThat(Schedules.interval("60", "SECONDS")).as("timeUnit").isEqualTo("1 min");
        assertThat(Schedules.interval("PT10M", null)).isEqualTo("10 min");
        assertThat(Schedules.interval("90s", null)).isEqualTo("90 s");
        assertThat(Schedules.interval("1500", null)).isEqualTo("1500 ms");
        assertThat(Schedules.interval("#{@props.windowMinutes * 60000}", null)).isEqualTo("#{@props.windowMinutes * 60000} (as written)");
    }

    @Test
    void whatIsNotUnderstoodSaysWhy() {
        assertThatThrownBy(() -> Cron.parse("0 0 * *", Cron.Format.UNIX)).hasMessageContaining("has 4 fields, a unix cron has 5");
        assertThatThrownBy(() -> Cron.parse("0 0 L * *", Cron.Format.UNIX)).hasMessageContaining("Quartz");
        assertThatThrownBy(() -> Cron.parse("61 0 * * *", Cron.Format.UNIX)).hasMessageContaining("outside the minute");
        assertThatThrownBy(() -> Cron.parse("0 0 * * FUNDAY", Cron.Format.UNIX)).hasMessageContaining("not a value of the day of week");
    }

    @Test
    void whatRunsOnADayAcrossServicesAndEnvironments() throws IOException {
        Files.writeString(tmp.resolve("facts.jsonl"), """
                {"header":{"schema":"kb-facts/0","extractor":"mapping","repo":"scheduler"}}
                {"node":{"id":"job:shipping:nightly-export","kind":"job","env":"prod","attrs":{"schedule":"0 2 * * 1-5","scheduleFormat":"unix"},"evidence":{"file":"V1__jobs.sql","line":7}}}
                {"node":{"id":"job:shipping:nightly-export","kind":"job","env":"dev","attrs":{"schedule":"*/10 * * * *","scheduleFormat":"unix"},"evidence":{"file":"V1__jobs.sql","line":7}}}
                {"node":{"id":"job:billing:settlement","kind":"job","env":"prod","attrs":{"schedule":"0 3 * * 0","scheduleFormat":"unix"},"evidence":{"file":"V1__jobs.sql","line":8}}}
                {"node":{"id":"job:billing:report","kind":"job","attrs":{"cron":"${report.cron}"},"evidence":{"file":"Report.java","line":12}}}
                {"node":{"id":"job:billing:cleanup","kind":"job","attrs":{"cron":"${cleanup.cron:0 0 1 * * *}","cronResolved":"0 30 1 * * *"},"evidence":{"file":"Cleanup.java","line":9}}}
                {"node":{"id":"job:billing:poll","kind":"job","attrs":{"fixedRate":"60000"},"evidence":{"file":"Poll.java","line":5}}}
                {"node":{"id":"job:billing:invoices","kind":"job","env":"prod","evidence":{"file":"V3__pipeline.sql","line":2}}}
                {"node":{"id":"job:stock:com.example.StockEnrichmentJob","kind":"job","attrs":{"entrypoint":"quartz","cron":"0 0 10 ? * * *","triggers":[{"name":"Stock_North_EveryDay","cron":"0 0 10 ? * * *"},{"name":"Stock_South_EveryDay","cron":"0 10 10 ? * * *"}]},"evidence":{"file":"StockEnrichmentJob.java","line":5}}}
                {"node":{"id":"job:batch:com.example.ArchiveJob","kind":"job","attrs":{"entrypoint":"quartz","cron":"0 15 1 * * ?","cronByProfile":{"prod":"0 15 2 ? * MON-FRI"}},"evidence":{"file":"ArchiveJob.java","line":5}}}
                {"edge":{"from":"job:shipping:nightly-export","kind":"after","to":"job:billing:invoices","env":"prod","provenance":"extracted","evidence":{"file":"V3__pipeline.sql","line":2}}}
                """);
        KnowledgeBase kb = KnowledgeBase.load(List.of(tmp));
        assertThat(kb.problems()).isEmpty();
        String monday = Schedules.runsOn(kb, "monday", "prod", null, LocalDate.parse("2026-11-20"));
        assertThat(monday).startsWith("2026-11-23 (monday), times in UTC, prod:")
                .contains("- 01:30:00  job:billing:cleanup  0 30 1 * * *", "- 02:00:00  job:shipping:nightly-export [prod]  0 2 * * 1-5",
                        "job:billing:poll: every 1 min", "then, in a pipeline:\n- job:billing:invoices after job:shipping:nightly-export",
                        "job:billing:report: '${report.cron}' has a placeholder with no value")
                .doesNotContain("settlement", "*/10");
        assertThat(monday.indexOf("cleanup")).as("by first time").isLessThan(monday.indexOf("nightly-export"));
        assertThat(Schedules.runsOn(kb, "sunday", "prod", null, LocalDate.parse("2026-11-20"))).contains("job:billing:settlement [prod]")
                .doesNotContain("pipeline");
        assertThat(Schedules.runsOn(kb, "2026-11-23", "dev", "Europe/Berlin", LocalDate.parse("2026-11-20")))
                .contains("times in Europe/Berlin, dev:", "job:shipping:nightly-export [dev]  */10 * * * *  (144 runs: 00:00:00, 00:10:00");
        assertThat(Schedules.nextRuns(kb, "job:shipping:nightly-export", "prod", 2, null, ZonedDateTime.parse("2026-11-20T10:00:00Z")))
                .contains("- Mon 2026-11-23 02:00:00", "- Tue 2026-11-24 02:00:00");
        assertThat(Schedules.nextRuns(kb, "job:billing:invoices", "prod", 2, null, ZonedDateTime.now(UTC))).contains("no schedule in the facts");
        // #197: a job with several triggers runs at each, named
        assertThat(Schedules.runsOn(kb, "monday", "prod", null, LocalDate.parse("2026-11-20")))
                .contains("- 10:00:00  job:stock:com.example.StockEnrichmentJob · Stock_North_EveryDay  0 0 10 ? * * *",
                        "- 10:10:00  job:stock:com.example.StockEnrichmentJob · Stock_South_EveryDay  0 10 10 ? * * *");
        assertThat(Schedules.nextRuns(kb, "job:stock:com.example.StockEnrichmentJob", "prod", 1, null, ZonedDateTime.parse("2026-11-20T10:05:00Z")))
                .contains("StockEnrichmentJob · Stock_North_EveryDay: 0 0 10 ? * * *\n- Sat 2026-11-21 10:00:00",
                        "StockEnrichmentJob · Stock_South_EveryDay: 0 10 10 ? * * *\n- Fri 2026-11-20 10:10:00");
        // #189: in prod the profile's cron (Quartz: MON-FRI), elsewhere the base one
        assertThat(Schedules.nextRuns(kb, "job:batch:com.example.ArchiveJob", "prod", 1, null, ZonedDateTime.parse("2026-11-20T10:00:00Z")))
                .contains("- Mon 2026-11-23 02:15:00");
        assertThat(Schedules.nextRuns(kb, "job:batch:com.example.ArchiveJob", "dev", 1, null, ZonedDateTime.parse("2026-11-20T10:00:00Z")))
                .contains("- Sat 2026-11-21 01:15:00");
    }

    /** #187: Quartz's dialect: weekdays 1 (Sunday) to 7, ? and an optional year. */
    @org.junit.jupiter.api.Test
    void quartz() {
        java.time.ZonedDateTime fri = java.time.ZonedDateTime.of(2026, 11, 20, 12, 0, 0, 0, java.time.ZoneOffset.UTC);   // a Friday
        Cron byName = Cron.parse("0 0 3 ? * MON-FRI", Cron.Format.QUARTZ);
        Cron byNumber = Cron.parse("0 0 3 ? * 2-6", Cron.Format.QUARTZ);
        org.assertj.core.api.Assertions.assertThat(byName.next(fri, 1).getFirst()).isEqualTo(fri.plusDays(3).withHour(3));
        org.assertj.core.api.Assertions.assertThat(byNumber.next(fri, 1).getFirst()).as("2 is Monday in Quartz").isEqualTo(fri.plusDays(3).withHour(3));
        org.assertj.core.api.Assertions.assertThat(Cron.parse("0 0 3 ? * 1", Cron.Format.QUARTZ).next(fri, 1).getFirst()).as("1 is Sunday")
                .isEqualTo(fri.plusDays(2).withHour(3));
        org.assertj.core.api.Assertions.assertThat(Cron.parse("0 0 3 1 1 ? 2028", Cron.Format.QUARTZ).next(fri, 1).getFirst())
                .isEqualTo(java.time.ZonedDateTime.of(2028, 1, 1, 3, 0, 0, 0, java.time.ZoneOffset.UTC));
    }
}
