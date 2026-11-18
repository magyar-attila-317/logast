package com.example.e2e.reactive;

import com.example.e2e.messaging.ReactivePledgeLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Spring WebFlux: each method assembles the pipeline the request runs through. */
@RestController
@RequestMapping("/reactive-pledges")
public class ReactivePledgeController {
    private static final Logger log = LoggerFactory.getLogger(ReactivePledgeController.class);
    private final ReactivePledgeLookup lookup;

    ReactivePledgeController(ReactivePledgeLookup lookup) {
        this.lookup = lookup;
    }

    @GetMapping("/{pledgeId}")
    public Mono<String> show(@PathVariable String pledgeId) {
        return lookup.find(pledgeId);
    }

    @PostMapping("/{pledgeId}/hold")
    public Mono<String> hold(@PathVariable String pledgeId) {
        return lookup.find(pledgeId).map(String::toUpperCase);
    }

    @GetMapping("/{pledgeId}/preview")
    public Mono<String> preview(@PathVariable String pledgeId) {
        return lookup.find(pledgeId)
                .onErrorResume(e -> Mono.just("unknown"));
    }

    @PostMapping("/{pledgeId}/release")
    public Mono<String> release(@PathVariable String pledgeId) {
        return lookup.find(pledgeId)
                .doOnNext(found -> log.info("Pledge released [pledgeId={}]", pledgeId));
    }

    @GetMapping("/recent")
    public Flux<String> recent() {
        return Flux.just("first", "second");
    }
}
