package com.example.e2e.crate;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class CrateController {
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
}
