package com.example.e2e.job;

import com.example.e2e.logging.Outcomes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("export")
public class ExportJob extends TpiJob {
    @Override
    protected void doExecute() {
        log.info("Coupon sweep started");
        log.info("Coupon sweep completed");
        Outcomes.count("export.job", "completed");
    }
}
