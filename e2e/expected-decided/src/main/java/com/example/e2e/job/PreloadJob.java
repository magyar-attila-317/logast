package com.example.e2e.job;

import com.example.e2e.logging.Outcomes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("preload")
public class PreloadJob extends TpiJob {
    private static final Logger log = LoggerFactory.getLogger(PreloadJob.class);

    @Override
    protected void doExecute() {
        log.info("Preload export started");
        log.info("Preload export completed");
        Outcomes.count("preload.export", "completed");
    }
}
