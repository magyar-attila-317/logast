package com.example.e2e.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("export")
public class ExportJob extends TpiJob {
    private static final Logger log = LoggerFactory.getLogger(ExportJob.class);

    @Override
    protected void doExecute() {
        log.info("Export job started"); // logast:wording
        log.info("Export job completed"); // logast:wording
    }
}
