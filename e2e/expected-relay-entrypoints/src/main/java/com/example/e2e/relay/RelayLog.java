package com.example.e2e.relay;

import org.springframework.stereotype.Component;

@Component
public class RelayLog {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RelayLog.class);

    void record(String what) {
        log.info("Relay recorded [what={}]", what);
    }

    /** Only the Redis listener calls it: the container logs what it rethrows. */
    void forward(byte[] body) {
        try {
            record(new String(body, java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw e;
        }
    }
}
