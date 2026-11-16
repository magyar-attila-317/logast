package com.example.e2e.job;

import com.example.e2e.logging.Outcomes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("export")
public class ExportJob extends SettlementJob {
    @Override
    protected void doExecute() {
        log.info("Export job started"); // logast:wording
        log.info("Export job completed"); // logast:wording; logast: METRIC_OUTCOME_UNMEASURED OUM-392e74
        Outcomes.count("export.job", "completed");
    }
}
