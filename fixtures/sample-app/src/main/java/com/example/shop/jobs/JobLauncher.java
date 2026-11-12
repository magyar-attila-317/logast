package com.example.shop.jobs;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Orchestrator side: starts batch jobs as pods or local processes. */
@Component
public class JobLauncher {
    public Process launchLocal(String job) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("java", "-jar", job + ".jar"); // expect: JOB_LAUNCH_UNTRACED
        return pb.start();
    }

    public Container podSpec(String image) {
        return new ContainerBuilder()
                .withName("batch-job")
                .withImage(image) // expect: JOB_LAUNCH_UNTRACED
                .build();
    }
}
