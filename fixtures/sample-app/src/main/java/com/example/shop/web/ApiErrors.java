package com.example.shop.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Error answers: a catch-all that leaks exception messages and logs nothing, a client error answered with 500, a
 * handler answering 200 (and one that sets its status).
 */
@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception e) { // expect: UNEXPECTED_ERROR_UNLOGGED // expect-not: CLIENT_ERROR_AS_SERVER_ERROR
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage()); // expect: ERROR_DETAILS_EXPOSED
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<String> missing(NoResourceFoundException e) { // expect: CLIENT_ERROR_AS_SERVER_ERROR
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("not found");
    }

    @ExceptionHandler(IllegalStateException.class)
    public String conflict(IllegalStateException e) { // expect: ERROR_HANDLER_STATUS_OK
        return "conflict";
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    @ResponseStatus(HttpStatus.NOT_IMPLEMENTED)
    public String unsupported(UnsupportedOperationException e) { // expect-not: ERROR_HANDLER_STATUS_OK
        return "unsupported";
    }
}
