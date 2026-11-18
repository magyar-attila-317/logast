package com.example.e2e.label;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class LabelErrors {
    private static final Logger log = LoggerFactory.getLogger(LabelErrors.class);

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Label request failed", e);
        LabelMetrics.labelRequestFailed(); // logast:metric review, confidence: medium
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
    }

    @ExceptionHandler(LabelStale.class)
    ProblemDetail stale(LabelStale e) {
        return new LabelProblem("stale label");
    }

    @ExceptionHandler(LabelLocked.class)
    ProblemDetail locked(LabelLocked e) {
        return new LabelGone();
    }
}
