package com.example.e2e.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Returns a Mono: the body only assembles the pipeline, a scope around it would end before the lookup runs. */
@Service
public class ReactivePledgeLookup {
    private static final Logger log = LoggerFactory.getLogger(ReactivePledgeLookup.class);

    public Mono<String> find(String pledgeId) {
        log.info("Pledge lookup started [pledge.id={}]", pledgeId);
        return Mono.just(pledgeId)
                .doOnNext(found -> log.debug("Pledge lookup matched [pledge.id={}]", pledgeId))
                .doOnError(e -> log.warn("Pledge lookup failed [pledge.id={}]", pledgeId, e));
    }
}
