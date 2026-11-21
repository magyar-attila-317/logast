package dev.logast.kb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** #219: a later step of a chain runs after the job before it, at the earliest when the chain starts: "≥ 01:00". */
class ChainTimesTest {
    @TempDir
    Path tmp;

    @Test
    void laterStepsOfAChainReadAsNoEarlierThan() throws IOException {
        Files.writeString(tmp.resolve("f.jsonl"), """
                {"header":{"schema":"kb-facts/0","extractor":"java","repo":"batch"}}
                {"node":{"id":"job:batch:LoadJob","kind":"job","attrs":{"entrypoint":"quartz","triggers":[{"cron":"0 0 1 ? * MON","name":"weekly-a","chain":"weekly-a","position":"1 of 2"}]},"evidence":{"file":"LoadJob.java"}}}
                {"node":{"id":"job:batch:TotalsJob","kind":"job","attrs":{"entrypoint":"quartz","triggers":[{"cron":"0 0 1 ? * MON","name":"weekly-a","chain":"weekly-a","position":"2 of 2","runsAfter":"LoadJob"}]},"evidence":{"file":"TotalsJob.java"}}}
                """);
        KnowledgeBase kb = KnowledgeBase.load(List.of(tmp));
        String day = Schedules.runsOn(kb, "2026-11-23", null, "UTC", LocalDate.of(2026, 11, 22));
        assertThat(day).contains("- 01:00:00  job:batch:LoadJob · weekly-a", "- ≥ 01:00:00  job:batch:TotalsJob · weekly-a, after LoadJob");
        assertThat(Schedules.nextRuns(kb, "job:batch:TotalsJob", null, 1, "UTC", ZonedDateTime.of(2026, 11, 22, 0, 0, 0, 0, ZoneOffset.UTC)))
                .contains("after LoadJob", "- ≥ Mon 2026-11-23 01:00:00");
        assertThat(CodeMode.run(kb, "return runs('job:batch:TotalsJob', '2026-11-23')").json()).contains("≥ 01:00", "after LoadJob");
    }
}
