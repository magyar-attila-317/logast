package com.example.e2e.job;

import com.example.e2e.metrics.BusinessMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("calculation")
public class CalculationJob extends SettlementJob {
    private static final Logger log = LoggerFactory.getLogger(CalculationJob.class);

    @Override
    protected void doExecute() {
        log.info("Coupon sweep started");
        log.info("Calculation completed");
        BusinessMetrics.calculationCompleted();
    }
}
