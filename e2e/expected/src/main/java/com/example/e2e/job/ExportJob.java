package com.example.e2e.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("export")
public class ExportJob extends TpiJob {
    @Override
    protected void doExecute() {
        log.info("Export job started"); // logast:wording
        log.info("Export job completed"); // logast:wording
    }
}
