package com.example.e2e.job;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("export")
public class ExportJob extends TpiJob {
    @Override
    protected void doExecute() {
    }
}
