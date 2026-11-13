package com.example.e2e.job;

import com.example.e2e.logging.ObservedContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** The step logs the base class's MDC key again. */
@Component
@Profile("publish")
public class PublishRunJob extends BaseRunJob {
    private static final Logger log = LoggerFactory.getLogger(PublishRunJob.class);

    @Override
    protected void doRun(String runCode) {
        ObservedContext.entrypoint("publishing")
                .quiet()
                .run(() -> {
                    log.info("Publishing started");
                    log.info("Publishing completed");
                });
    }
}
