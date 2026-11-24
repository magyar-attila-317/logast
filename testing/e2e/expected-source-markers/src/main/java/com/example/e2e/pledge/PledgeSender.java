package com.example.e2e.pledge;

import com.example.e2e.logging.MdcScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PledgeSender {
    private static final Logger log = LoggerFactory.getLogger(PledgeSender.class);

    public void send(String pledgeId) {
        log.debug("Sending pledge to the channel");
        log.info("Pledge sent"); // logast: METRIC_OUTCOME_UNMEASURED OUM-e7708e
    }

    /** A failure logged and handled here: an outcome worth counting by default. */
    public boolean resend(String pledgeId) {
        try {
            try (var mdc = MdcScope.of("pledgeId", pledgeId)) {
                send(pledgeId);
                return true;
            }
        } catch (IllegalStateException e) {
            log.warn("Pledge resend failed [pledgeId={}]", pledgeId, e);
            PledgeMetrics.pledgeResendFailed(); // logast:metric review, confidence: medium
            return false;
        }
    }
}
