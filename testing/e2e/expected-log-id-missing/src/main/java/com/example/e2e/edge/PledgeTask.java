package com.example.e2e.edge;

import lombok.extern.slf4j.Slf4j;

/** The id lives in a field for the object's lifetime. */
@Slf4j
public class PledgeTask {
    private final String pledgeId;

    public PledgeTask(String pledgeId) {
        this.pledgeId = pledgeId;
    }

    public void run() {
        log.info("Task step one");
        log.info("Task step two");
    }
}
