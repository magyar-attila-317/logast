package com.example.e2e.intake;

import com.example.e2e.logging.ObservedContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/** One step of the nightly import job. */
@Slf4j
@Component
public class PledgeImportTasklet implements Tasklet {
    private final PledgeFeed feed;

    PledgeImportTasklet(PledgeFeed feed) {
        this.feed = feed;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        return ObservedContext.entrypoint(IntakeMetrics.PLEDGE_IMPORT_TASKLET_EXECUTE)
                .call(() -> {
                    log.info("Pledge import tasklet started"); // logast:wording
                    feed.load();
                    return RepeatStatus.FINISHED;
                });
    }
}
