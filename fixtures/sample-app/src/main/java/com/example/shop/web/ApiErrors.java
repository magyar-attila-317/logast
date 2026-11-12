package com.example.shop.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Error answers: a catch-all that leaks exception messages, a client error answered with 500. */
@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage()); // expect: ERROR_DETAILS_EXPOSED
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<String> missing(NoResourceFoundException e) { // expect: CLIENT_ERROR_AS_SERVER_ERROR
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("not found");
    }
}
