package com.example.e2e.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("tally")
public class TallyJob extends SettlementJob {
    private static final Logger log = LoggerFactory.getLogger(TallyJob.class);

    @Override
    protected void doExecute() {
        log.info("Tally started"); // logast:wording
        log.info("Tally completed");
        JobMetrics.tallyCompleted(); // logast:metric review, confidence: medium
    }
}
