package com.example.e2e.crate;

public class CrateMissing extends RuntimeException {
    public CrateMissing(String message) {
        super(message);
    }
}
