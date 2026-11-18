package com.example.e2e.web;

import com.example.e2e.pledge.PledgeMissing;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps a missing pledge to 404 without logging it. */
@RestControllerAdvice
public class PledgeErrors {
    @ExceptionHandler(PledgeMissing.class)
    public ResponseEntity<Void> missing(PledgeMissing e) {
        return ResponseEntity.notFound().build();
    }
}
