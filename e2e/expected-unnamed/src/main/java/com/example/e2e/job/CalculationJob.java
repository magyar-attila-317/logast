package com.example.e2e.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("calculation")
public class CalculationJob extends TpiJob {
    private static final Logger log = LoggerFactory.getLogger(CalculationJob.class);

    @Override
    protected void doExecute() {
        log.info("Calculation started"); // logast:wording
        log.info("Calculation completed");
    }
}
