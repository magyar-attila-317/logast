package com.example.e2e.job;

import com.example.e2e.metrics.BusinessMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("preload")
public class PreloadJob extends SettlementJob {
    private static final Logger log = LoggerFactory.getLogger(PreloadJob.class);

    @Override
    protected void doExecute() {
        log.info("Preload export started");
        log.info("Preload export completed"); // logast: METRIC_OUTCOME_UNMEASURED OUM-553423
        BusinessMetrics.preloadExportCompleted();
    }
}
