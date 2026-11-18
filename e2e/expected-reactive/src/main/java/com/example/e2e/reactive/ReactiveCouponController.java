package com.example.e2e.reactive;

import com.example.e2e.messaging.ReactiveCouponLookup;
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
@RequestMapping("/reactive-coupons")
public class ReactiveCouponController {
    private static final Logger log = LoggerFactory.getLogger(ReactiveCouponController.class);
    private final ReactiveCouponLookup lookup;

    ReactiveCouponController(ReactiveCouponLookup lookup) {
        this.lookup = lookup;
    }

    @GetMapping("/{couponId}")
    public Mono<String> show(@PathVariable String couponId) {
        return lookup.find(couponId);
    }

    @PostMapping("/{couponId}/hold")
    public Mono<String> hold(@PathVariable String couponId) {
        return lookup.find(couponId).map(String::toUpperCase);
    }

    @GetMapping("/{couponId}/preview")
    public Mono<String> preview(@PathVariable String couponId) {
        return lookup.find(couponId)
                .onErrorResume(e -> Mono.just("unknown"));
    }

    @PostMapping("/{couponId}/release")
    public Mono<String> release(@PathVariable String couponId) {
        return lookup.find(couponId)
                .doOnNext(found -> log.info("Coupon released [couponId={}]", couponId));
    }

    @GetMapping("/recent")
    public Flux<String> recent() {
        return Flux.just("first", "second");
    }
}
