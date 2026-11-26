package com.example.e2e.order;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** #335: values named after what they are: a helper's noun (describeHost → host), a ternary of the message (reason). */
@Component
public class DepotRouting {
    private static final Logger log = LoggerFactory.getLogger(DepotRouting.class);

    public void route(String url) {
        log.info("pool targets {}", describeHost(url));
        try {
            connect(url);
        } catch (RuntimeException e) {
            log.warn("Rejecting route {}", e instanceof IllegalStateException ? e.getMessage() : "malformed route");
        }
    }

    String describeHost(String url) {
        return URI.create(url).getHost();
    }

    void connect(String url) {
        URI.create(url);
    }
}
