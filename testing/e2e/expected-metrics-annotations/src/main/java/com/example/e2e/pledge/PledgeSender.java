package com.example.e2e.pledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PledgeSender {
    private static final Logger log = LoggerFactory.getLogger(PledgeSender.class);

    public void send(String pledgeId) {
        log.debug("Sending pledge {} to the channel", pledgeId);
        log.info("Pledge sent, pledgeId={}", pledgeId);
    }

    /** A failure logged and handled here: an outcome worth counting by default. */
    public boolean resend(String pledgeId) {
        try {
            send(pledgeId);
            return true;
        } catch (IllegalStateException e) {
            log.warn("Pledge resend failed [pledgeId={}]", pledgeId, e);
            PledgeMetrics.pledgeResendFailed();
            return false;
        }
    }
}
