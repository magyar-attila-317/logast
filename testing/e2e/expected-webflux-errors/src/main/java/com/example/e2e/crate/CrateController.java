package com.example.e2e.crate;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@RestController
public class CrateController {
    private final WebClient labels = WebClient.create("http://labels");

    @PostMapping("/crates")
    public Mono<String> register(@Valid @RequestBody CrateRequest request) {
        return Mono.just(request.label());
    }

    @PutMapping("/crates/{crateCode}")
    public Mono<String> relabel(@PathVariable String crateCode, @RequestBody CrateRequest request) {
        return Mono.just(crateCode + request.label());
    }

    @GetMapping("/crates/{crateCode}")
    public Mono<String> show(@PathVariable String crateCode) {
        return crateCode.isBlank() ? Mono.error(new CrateMissing("no crate code")) : Mono.just(crateCode);
    }

    @PostMapping("/crates/{crateCode}/seal")
    public Mono<String> seal(@PathVariable String crateCode) {
        return Mono.error(() -> new CrateSealed(crateCode));
    }

    @GetMapping("/crates/{crateCode}/label")
    public Mono<String> label(@PathVariable String crateCode) {
        return labels.get().uri("/labels/{code}", crateCode).retrieve().bodyToMono(String.class);
    }

    @GetMapping("/crates/{crateCode}/fallback-label")
    public Mono<String> fallbackLabel(@PathVariable String crateCode) {
        return labels.get().uri("/labels/{code}", crateCode).retrieve().bodyToMono(String.class)
                .onErrorResume(e -> Mono.just("unlabelled"));
    }
}
