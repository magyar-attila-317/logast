package com.example.e2e.crate;

public class CrateBlocked extends RuntimeException {
    public CrateBlocked(String message) {
        super(message);
    }
}
