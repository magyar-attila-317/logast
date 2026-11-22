package com.example.e2e.parcel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
class ParcelErrors {
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Request failed unexpectedly", e); // logast:wording
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
    }

    @ExceptionHandler(ParcelHeld.class)
    ParcelAnswer held(ParcelHeld e) {
        return new ParcelAnswer("held");
    }

    @ExceptionHandler(ParcelLate.class)
    ResponseEntity<String> late(ParcelLate e) {
        return ResponseEntity.ok("late");
    }

    @ExceptionHandler(ParcelLost.class)
    @ResponseStatus(HttpStatus.GONE)
    ParcelAnswer lost(ParcelLost e) {
        return new ParcelAnswer("lost");
    }
}
