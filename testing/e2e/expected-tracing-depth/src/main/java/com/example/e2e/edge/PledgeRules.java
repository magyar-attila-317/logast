package com.example.e2e.edge;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

/** Lombok makes these methods static at compile time: the source declares them as instance methods. */
@Slf4j
@UtilityClass
public class PledgeRules {
    public void check(String pledgeId) {
        log.debug("Checking rules [pledgeId={}]", pledgeId);
        if (pledgeId.isBlank()) {
            log.debug("No rules apply [pledgeId={}]", pledgeId);
        }
    }
}
