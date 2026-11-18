package com.example.e2e.crate;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import reactor.core.publisher.Mono;

@Slf4j
@RestControllerAdvice
public class CrateErrors {
    @ExceptionHandler(Exception.class)
    Mono<ResponseEntity<ProblemDetail>> unexpected(Exception e) {
        log.error("Request failed unexpectedly", e); // logast:wording
        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR)));
    }

    @ExceptionHandler(CrateMissing.class)
    Mono<ProblemDetail> missing(CrateMissing e) {
        return Mono.just(ProblemDetail.forStatus(HttpStatus.NOT_FOUND));
    }

    @ExceptionHandler(CrateLate.class)
    Mono<CrateAnswer> late(CrateLate e) {
        return Mono.just(new CrateAnswer("late"));
    }

    @ExceptionHandler(CrateBlocked.class)
    Mono<CrateAnswer> blocked(CrateBlocked e, ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.LOCKED);
        return Mono.just(new CrateAnswer("blocked"));
    }
}
